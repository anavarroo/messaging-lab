"""
La API del asistente: el mismo contrato que tenía el backend en la especificación 06 (`/api/assistant/status` y
`/api/assistant/chat`, con los eventos `token`, `done` y `error`), así que el frontend no cambia. Las comprobaciones se
hacen antes de empezar a responder, para devolver un error HTTP normal; la respuesta llega después por el flujo.
"""

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from contextlib import aclosing, asynccontextmanager
from typing import Literal

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from app import docs as documentation
from app.backend import BackendClient
from app.config import Settings
from app.docs import DocIndex
from app.errors import AssistantError, BusyError, ValidationError
from app.llm import OllamaLlm
from app.service import AssistantService, Pending, Turn

log = logging.getLogger(__name__)

FAILED = "El asistente ha fallado. Inténtalo de nuevo en un momento."


class AssistantTurn(BaseModel):
    role: Literal["USER", "ASSISTANT"]
    text: str = Field(max_length=2000)


class AssistantChatRequest(BaseModel):
    # la longitud de la pregunta la comprueba el servicio, para dar su mensaje en español
    question: str | None = None
    history: list[AssistantTurn] = Field(default_factory=list, max_length=8)


def create_app(service: AssistantService | None = None) -> FastAPI:
    """La aplicación. Sin `service`, lo construye con la configuración del entorno al arrancar."""

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        indexing = None
        if service is None:
            logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
            # una línea por cada consulta a Ollama y al backend es demasiado
            logging.getLogger("httpx").setLevel(logging.WARNING)
            settings = Settings.from_env()
            llm = OllamaLlm(settings.ollama_url, settings.model, settings.embedding_model,
                            {"num_ctx": settings.context_tokens, "num_predict": settings.max_response_tokens,
                             "temperature": settings.temperature})
            backend = BackendClient(settings.backend_url)
            index = DocIndex(llm, settings.embedding_model, settings.index_dir)
            app.state.service = AssistantService(backend, llm, index, settings.model, settings.embedding_model)
            # el índice se construye en segundo plano: mientras, el estado es «preparándose»
            indexing = asyncio.create_task(
                index.keep_built(documentation.load(settings.docs_dir), app.state.service.embedding_model_ready))
        else:
            app.state.service = service
        yield
        if indexing:
            indexing.cancel()

    app = FastAPI(title="messaging-lab assistant", lifespan=lifespan)

    @app.exception_handler(AssistantError)
    async def assistant_error(_: Request, error: AssistantError) -> JSONResponse:
        if isinstance(error, ValidationError):
            return _error(400, "VALIDATION", error.message)
        if isinstance(error, BusyError):
            return _error(409, "CONFLICT", error.message)
        # UnavailableError y cualquier otro fallo antes de empezar a responder
        return _error(503, "ASSISTANT_UNAVAILABLE", error.message)

    @app.exception_handler(RequestValidationError)
    async def invalid_body(_: Request, error: RequestValidationError) -> JSONResponse:
        fields = [str(part) for part in error.errors()[0].get("loc", ())[1:2]] if error.errors() else []
        return _error(400, "VALIDATION", f"El campo «{fields[0]}» no es válido." if fields
                      else "El cuerpo de la petición no es válido.")

    @app.get("/api/assistant/status")
    async def status(request: Request) -> dict:
        current = await request.app.state.service.status()
        return {"state": current.state, "message": current.message}

    @app.post("/api/assistant/chat")
    async def chat(body: AssistantChatRequest, request: Request) -> StreamingResponse:
        pending = await request.app.state.service.begin(body.question,
                                                        [Turn(turn.role, turn.text) for turn in body.history])
        return StreamingResponse(events(pending), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    return app


async def events(pending: Pending) -> AsyncIterator[str]:
    """
    Los eventos de la respuesta. Si el cliente se va, Starlette cancela el flujo y al cerrarse se corta la generación
    del modelo y se libera el asistente.
    """
    try:
        async with aclosing(pending.run()) as tokens:
            async for token in tokens:
                yield _event("token", {"text": token})
        yield _event("done", {})
    except AssistantError as error:
        # el modelo falla a mitad de la respuesta: lo recibido se conserva y se explica lo ocurrido
        yield _event("error", {"message": error.message})
    except Exception:
        log.exception("Fallo al responder")
        yield _event("error", {"message": FAILED})
    finally:
        pending.close()


def _event(name: str, data: dict) -> str:
    return f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse({"code": code, "message": message}, status_code=status)


app = create_app()
