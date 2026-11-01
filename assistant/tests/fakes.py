"""Un backend y un Ollama falsos, para probar la lógica sin red."""

import json
from typing import Any

import httpx

from app.backend import BackendClient
from app.errors import UnavailableError
from app.llm import Chunk, ToolCall


def snapshot(queues=(), exchanges=(), emitters=(), consumers=(), available=True, trace="ACTIVE", rate=0.0,
             omitted=0) -> dict[str, Any]:
    return {"time": "2026-10-31T10:00:00Z",
            "broker": {"available": available, "version": "4.4.0" if available else None,
                       "reason": None if available else "sin respuesta"},
            "trace": {"state": trace if available else "UNKNOWN", "hint": None},
            "queues": list(queues), "exchanges": list(exchanges), "emitters": list(emitters),
            "consumers": list(consumers),
            "load": {"ratePerSecondInUse": rate, "maxRatePerSecond": 100, "omittedPerSecond": omitted}}


def queue(name, ready=0, consumers=1, rate_in=None, rate_out=None, unacked=0, dlq=None) -> dict[str, Any]:
    return {"name": name, "ready": ready, "unacknowledged": unacked, "deadLetterQueue": dlq, "consumers": consumers,
            "inPerSecond": rate_in, "outPerSecond": rate_out}


def emitter(name, status="RUNNING", rate=0.0, published=0, unrouted=0, destination="la cola «cocina»"):
    return {"name": name, "status": status, "destination": destination, "ratePerSecond": rate,
            "published": published, "unrouted": unrouted, "stopMessage": None}


def consumer(name, queue_name, status="RUNNING", rate=0.0, processed=0, failed=0):
    return {"name": name, "queue": queue_name, "status": status, "ratePerSecond": rate, "processed": processed,
            "failed": failed, "stopMessage": None}


def finding(text, severity="WARNING", topic="QUEUES") -> dict[str, str]:
    return {"severity": severity, "topic": topic, "text": text}


class FakeBackend:
    """Responde como el backend y apunta cada petición. `down` simula que no responde."""

    def __init__(self, lab: dict[str, Any] | None = None, findings: list | None = None, activity: str = "Nada se mueve.",
                 active: bool = False):
        self.lab = lab or snapshot()
        self.findings = findings or []
        self.activity = activity
        self.active = active
        self.down = False
        self.requests: list[tuple[str, str]] = []

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append((request.method, request.url.path))
        if self.down:
            raise httpx.ConnectError("sin conexión", request=request)
        body = {"/api/status": {"status": "AVAILABLE"},
                "/api/lab/snapshot": self.lab,
                "/api/lab/diagnostics": {"brokerAvailable": self.lab["broker"]["available"],
                                         "findings": self.findings},
                "/api/lab/activity": {"active": self.active, "text": self.activity}}.get(request.url.path)
        return httpx.Response(404) if body is None else httpx.Response(200, content=json.dumps(body))

    def client(self) -> BackendClient:
        return BackendClient("http://backend", transport=httpx.MockTransport(self.handle))


class FakeLlm:
    """
    Un modelo con respuestas preparadas: cada elemento de `rounds` es una vuelta, con los trozos que devuelve (texto
    o peticiones de herramientas) o una excepción. Apunta qué se le pidió y si se cerró el flujo.
    """

    def __init__(self, *rounds, models=("qwen2.5:3b", "bge-m3:latest"), vectors=None):
        self.rounds = list(rounds)
        self.models_available = set(models)
        self.down = False
        self.calls: list[dict[str, Any]] = []
        self.closed = 0
        self.vectors = vectors or (lambda text: [1.0, 0.0])
        self.on_token = None

    async def models(self) -> set[str]:
        if self.down:
            raise UnavailableError("Ollama no responde")
        return self.models_available

    async def chat(self, messages, tools=None):
        self.calls.append({"messages": [dict(message) for message in messages], "tools": tools})
        script = self.rounds.pop(0) if self.rounds else ["(sin guion)"]
        try:
            if isinstance(script, Exception):
                raise script
            for part in script:
                if self.on_token:
                    self.on_token()
                yield Chunk(tool_calls=(part,)) if isinstance(part, ToolCall) else Chunk(part)
        finally:
            self.closed += 1

    async def embed(self, texts):
        if self.down:
            raise UnavailableError("Ollama no responde")
        return [self.vectors(text) for text in texts]
