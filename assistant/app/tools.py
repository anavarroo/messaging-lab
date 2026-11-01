"""
Las herramientas que el modelo puede usar. Todas son de solo lectura (consultas GET al backend o búsquedas en la
documentación) y devuelven texto en español, ya resumido y de tamaño acotado. Cuando llegue Kafka se añaden aquí las
suyas, sin cambiar el resto del asistente.
"""

import inspect
from collections.abc import Awaitable, Callable
from typing import Any

from app import context
from app.backend import BackendClient
from app.docs import DocIndex
from app.errors import AssistantError
from app.words import normalize

# tokens que, como mucho, devuelve una herramienta
TOOL_BUDGET_TOKENS = 500


def _function(name: str, description: str, **parameters: str) -> dict[str, Any]:
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": {"type": "object",
                       "properties": {key: {"type": "string", "description": text} for key, text in parameters.items()},
                       "required": [key for key in parameters if key == "consulta"]}}}


DEFINITIONS: list[dict[str, Any]] = [
    _function("estado_laboratorio", "Estado de RabbitMQ (levantado o no, versión), del trazado y cuántas colas, "
                                    "exchanges, emisores y consumidores hay."),
    _function("colas", "Colas con mensajes listos, pendientes de confirmar, consumidores, ritmos y dead-letter queue.",
              nombre="Nombre de una cola, si se pregunta por una en concreto"),
    _function("exchanges", "Exchanges con su tipo y las colas a las que están enlazados (bindings).",
              nombre="Nombre de un exchange, si se pregunta por uno en concreto"),
    _function("generador", "Emisores y consumidores simulados del generador, con su estado, ritmo y contadores, y el "
                           "límite global de mensajes por segundo."),
    _function("actividad", "Qué mensajes se están moviendo ahora mismo."),
    _function("diagnostico", "Problemas detectados en el laboratorio, con su causa y su solución."),
    _function("buscar_documentacion", "Busca en la guía, el README y las especificaciones del laboratorio. Úsala para "
                                      "explicar conceptos.",
              consulta="Lo que se quiere saber, en español"),
]

NAMES = frozenset(definition["function"]["name"] for definition in DEFINITIONS)


class Toolbox:

    def __init__(self, backend: BackendClient, docs: DocIndex):
        self._backend = backend
        self._docs = docs
        self._tools: dict[str, Callable[..., Awaitable[str]]] = {
            "estado_laboratorio": self.lab_state, "colas": self.queues, "exchanges": self.exchanges,
            "generador": self.generator, "actividad": self.activity, "diagnostico": self.diagnostics,
            "buscar_documentacion": self.search_docs}

    async def run(self, name: str, arguments: dict[str, Any] | None = None) -> str:
        """El resultado de la herramienta, o un texto que explica por qué no se ha podido usar."""
        tool = self._tools.get(name)
        if tool is None:
            return f"No existe la herramienta «{name}». Las que hay son: {', '.join(sorted(NAMES))}."
        # los datos que la herramienta no admite se ignoran: un modelo pequeño a veces se los inventa
        accepted = inspect.signature(tool).parameters
        try:
            return await tool(**{key: str(value) for key, value in (arguments or {}).items() if key in accepted})
        except AssistantError as error:
            return error.message

    async def lab_state(self) -> str:
        snapshot = await self._backend.snapshot()
        lines = context.Lines(TOOL_BUDGET_TOKENS)
        context.state(snapshot, lines)
        context.trace(snapshot, lines)
        if not snapshot["broker"]["available"] and snapshot["broker"].get("reason"):
            lines.add(f"- Motivo: {snapshot['broker']['reason']}")
        return lines.text()

    async def queues(self, nombre: str = "") -> str:
        snapshot = await self._backend.snapshot()
        lines = context.Lines(TOOL_BUDGET_TOKENS)
        context.queues(snapshot, lines, normalize(nombre))
        return lines.text()

    async def exchanges(self, nombre: str = "") -> str:
        snapshot = await self._backend.snapshot()
        lines = context.Lines(TOOL_BUDGET_TOKENS)
        context.exchanges(snapshot, lines, normalize(nombre))
        return lines.text()

    async def generator(self) -> str:
        snapshot = await self._backend.snapshot()
        lines = context.Lines(TOOL_BUDGET_TOKENS)
        context.emitters(snapshot, lines)
        context.consumers(snapshot, lines)
        return lines.text()

    async def activity(self) -> str:
        return (await self._backend.activity())["text"]

    async def diagnostics(self) -> str:
        findings = (await self._backend.diagnostics())["findings"]
        if not findings:
            return "No se ha detectado ningún problema."
        lines = context.Lines(TOOL_BUDGET_TOKENS)
        for finding in findings[:context.MAX_FINDINGS]:
            lines.add("- " + finding["text"])
        return lines.text()

    async def search_docs(self, consulta: str = "") -> str:
        if not self._docs.ready:
            return "La búsqueda en la documentación todavía se está preparando."
        lines = context.Lines(TOOL_BUDGET_TOKENS)
        context.documentation(await self._docs.search(consulta), lines)
        return lines.text() or "No hay nada en la documentación sobre eso."
