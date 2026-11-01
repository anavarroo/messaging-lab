"""
El modelo de lenguaje y el de embeddings, servidos por Ollama. Se habla con su API con el cliente oficial: la lista
de modelos descargados, el chat en streaming (con herramientas) y los embeddings.
"""

from collections.abc import AsyncIterator, Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx
import ollama

from app.errors import AssistantError, ToolsNotSupportedError, UnavailableError

UNAVAILABLE = ("El modelo de IA no está disponible. Si has desactivado el modelo (OLLAMA_REPLICAS=0), el asistente no "
               "se puede usar; si no, comprueba que el contenedor «ollama» está en marcha con «docker compose ps».")

FAILED = "El modelo ha fallado al responder. Inténtalo de nuevo en un momento."

CUT = ("…\n\n(La respuesta era demasiado larga y se ha cortado. Pregunta por una parte concreta para ver el resto.)")


@dataclass(frozen=True)
class ToolCall:
    name: str
    arguments: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Chunk:
    """Un trozo de la respuesta del modelo: texto, peticiones de herramientas o las dos cosas."""

    content: str = ""
    tool_calls: tuple[ToolCall, ...] = ()


class Llm(Protocol):

    async def models(self) -> set[str]:
        """Los modelos descargados. Falla con `UnavailableError` si Ollama no responde."""

    def chat(self, messages: list[dict[str, Any]], tools: Sequence[dict[str, Any]] | None = None) -> AsyncIterator[Chunk]:
        """La respuesta por trozos. Cerrar el iterador corta la generación."""

    async def embed(self, texts: list[str]) -> list[list[float]]:
        """Un vector por texto, con el modelo de embeddings."""


def has_model(models: set[str], name: str) -> bool:
    """Ollama lista «bge-m3» como «bge-m3:latest»."""
    return name in models or (":" not in name and f"{name}:latest" in models)


class OllamaLlm:

    def __init__(self, url: str, model: str, embedding_model: str, options: dict[str, Any]):
        self._client = ollama.AsyncClient(host=url, timeout=httpx.Timeout(120.0, connect=3.0))
        self._model = model
        self._embedding_model = embedding_model
        self._options = options

    async def models(self) -> set[str]:
        try:
            response = await self._client.list()
        except Exception as error:
            raise UnavailableError(UNAVAILABLE) from error
        return {model.model for model in response.models if model.model}

    async def chat(self, messages: list[dict[str, Any]], tools: Sequence[dict[str, Any]] | None = None) -> AsyncIterator[Chunk]:
        stream = None
        try:
            stream = await self._client.chat(model=self._model, messages=messages, tools=tools or None, stream=True,
                                             options=self._options)
            async for part in stream:
                calls = tuple(ToolCall(call.function.name, dict(call.function.arguments or {}))
                              for call in part.message.tool_calls or ())
                if part.message.content or calls:
                    yield Chunk(part.message.content or "", calls)
                # se ha quedado sin tokens de respuesta (ASSISTANT_MAX_RESPONSE_TOKENS): mejor decirlo que dejarla a medias
                if part.done and part.done_reason == "length":
                    yield Chunk(CUT)
        except AssistantError:
            raise
        except ollama.ResponseError as error:
            if "does not support tools" in str(error):
                raise ToolsNotSupportedError(FAILED) from error
            if error.status_code == 404:
                raise UnavailableError(f"El modelo «{self._model}» todavía no está descargado. Vuelve a probar en un "
                                       "rato.") from error
            raise AssistantError(FAILED) from error
        except (ConnectionError, httpx.HTTPError) as error:
            raise UnavailableError(UNAVAILABLE) from error
        finally:
            # si se cancela la pregunta, cerrar la conexión hace que Ollama deje de generar
            if stream is not None:
                await stream.aclose()

    async def embed(self, texts: list[str]) -> list[list[float]]:
        try:
            response = await self._client.embed(model=self._embedding_model, input=texts)
        except Exception as error:
            raise UnavailableError(UNAVAILABLE) from error
        return [list(vector) for vector in response.embeddings]
