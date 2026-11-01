import asyncio
import json
from contextlib import aclosing

import httpx
import pytest

from app.main import create_app, events
from tests.fakes import finding, snapshot


@pytest.fixture
def client(service):
    app = create_app(service)
    app.state.service = service
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://assistant")


def parse(body: str) -> list[tuple[str, dict]]:
    """Los eventos del flujo, como los lee el frontend: bloques con `event:` y `data:`."""
    parsed = []
    for block in body.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        parsed.append((lines["event"], json.loads(lines["data"])))
    return parsed


async def test_the_status_has_the_contract_shape(client, llm):
    assert (await client.get("/api/assistant/status")).json() == {"state": "READY", "message": None}
    llm.down = True
    client._transport.app.state.service._status = None
    body = (await client.get("/api/assistant/status")).json()
    assert body["state"] == "UNAVAILABLE" and body["message"]


async def test_the_answer_arrives_as_token_events_and_ends_with_done(client, llm):
    llm.rounds = [["Un binding ", "es una regla."]]
    response = await client.post("/api/assistant/chat", json={
        "question": "¿Qué es un binding?", "history": [{"role": "USER", "text": "hola"}, {"role": "ASSISTANT", "text": "¡Hola!"}]})
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert parse(response.text) == [("token", {"text": "Un binding "}), ("token", {"text": "es una regla."}), ("done", {})]


async def test_a_direct_answer_is_a_single_token(client, backend):
    backend.findings = [finding("CAUSA: la cola «barra» no tiene consumidor")]
    response = await client.post("/api/assistant/chat", json={"question": "¿Está todo bien?"})
    assert parse(response.text) == [("token", {"text": "He detectado una cosa que revisar:\n- CAUSA: la cola «barra» no tiene consumidor"}),
                                    ("done", {})]


async def test_a_failure_halfway_keeps_what_was_sent_and_ends_with_error(client, llm):
    async def broken(messages, tools=None):
        yield type("Chunk", (), {"content": "Empiezo ", "tool_calls": ()})()
        raise RuntimeError("roto")

    llm.chat = broken
    response = await client.post("/api/assistant/chat", json={"question": "¿Qué es un binding?"})
    assert parse(response.text) == [("token", {"text": "Empiezo "}),
                                    ("error", {"message": "El asistente ha fallado. Inténtalo de nuevo en un momento."})]


@pytest.mark.parametrize("body, message", [
    ({"question": ""}, "Escribe una pregunta."),
    ({}, "Escribe una pregunta."),
    ({"question": "x" * 501}, "La pregunta no puede superar los 500 caracteres."),
    ({"question": "¿y?", "history": [{"role": "SYSTEM", "text": "x"}]}, "El campo «history» no es válido."),
    ({"question": "¿y?", "history": [{"role": "USER", "text": "x"}] * 9}, "El campo «history» no es válido."),
])
async def test_invalid_requests_are_400_with_an_api_error(client, body, message):
    response = await client.post("/api/assistant/chat", json=body)
    assert response.status_code == 400
    assert response.json() == {"code": "VALIDATION", "message": message}


async def test_a_second_question_at_the_same_time_is_409(client, service):
    await service.begin("¿Qué es un binding?")
    response = await client.post("/api/assistant/chat", json={"question": "¿Y una cola?"})
    assert response.status_code == 409
    assert response.json()["code"] == "CONFLICT"


async def test_an_assistant_that_is_not_ready_is_503(client, llm):
    llm.models_available = set()
    response = await client.post("/api/assistant/chat", json={"question": "¿Qué es un binding?"})
    assert response.status_code == 503
    assert response.json()["code"] == "ASSISTANT_UNAVAILABLE"
    assert "todavía no está descargado" in response.json()["message"]


async def test_rabbitmq_down_still_answers_through_the_stream(client, backend):
    backend.lab = snapshot(available=False)
    backend.findings = [finding("CAUSA: RabbitMQ no está disponible.", "CRITICAL", "STATE")]
    response = await client.post("/api/assistant/chat", json={"question": "¿Cómo va la cola cocina?"})
    assert parse(response.text)[0] == ("token", {"text": "CAUSA: RabbitMQ no está disponible."})


async def test_when_the_client_goes_away_the_generation_stops_and_the_assistant_is_freed(service, llm):
    started = asyncio.Event()

    async def endless(messages, tools=None):
        try:
            while True:
                started.set()
                yield type("Chunk", (), {"content": "bla ", "tool_calls": ()})()
                await asyncio.sleep(0)
        finally:
            llm.closed += 1

    llm.chat = endless
    pending = await service.begin("¿Qué es un binding?")
    async with aclosing(events(pending)) as stream:
        assert (await anext(stream)).startswith("event: token")
    # al cerrar el flujo (lo que hace Starlette cuando el navegador se va) se corta el modelo y se libera
    assert started.is_set() and llm.closed == 1
    llm.rounds = [["libre"]]
    del llm.chat
    assert (await service.begin("¿Otra?")) is not None
