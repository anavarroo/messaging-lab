"""
Prepara lo que el modelo va a leer: primero las conclusiones ya sacadas por el backend, después los datos relevantes
para la pregunta, ya resumidos, y por último las fichas del manual y los trozos de documentación que encajen. Todo en
un texto corto que cabe en un presupuesto fijo, sin cuerpos de mensajes. Portado de la especificación 06.

Las mismas funciones de cada sección son las que devuelven las herramientas, para que el modelo lea siempre lo mismo.
"""

from collections.abc import Iterable
from typing import Any

from app import knowledge
from app.classifier import Topic
from app.words import normalize, number

# cuántas conclusiones, como máximo, entran en el contexto
MAX_FINDINGS = 6

MAX_CARDS_FOR_CONCEPTS = 2

# caracteres que, como mucho, ocupa cada trozo de documentación en el contexto
MAX_DOC_CHARS = 700

Snapshot = dict[str, Any]
Finding = dict[str, str]

_STATUS = {"RUNNING": "en marcha", "PAUSED": "en pausa", "STOPPED": "parado", "FINISHED": "terminado",
           "FAILED": "fallado"}


def tokens(text: str) -> int:
    """Estimación prudente de los tokens de un texto en español (con nombres y símbolos, unos 3 caracteres por token)."""
    return (len(text) + 2) // 3


class Lines:
    """Las líneas del contexto, que dejan de añadirse cuando se agota el presupuesto."""

    def __init__(self, budget_tokens: int):
        self._lines: list[str] = []
        self._remaining = budget_tokens

    def add(self, line: str) -> bool:
        """Añade la línea si cabe; si no, devuelve `False` y no añade nada."""
        cost = tokens(line) + 1
        if cost > self._remaining:
            return False
        self._remaining -= cost
        self._lines.append(line)
        return True

    def add_always(self, line: str) -> None:
        """Añade una línea de aviso aunque justo no quepa."""
        self._remaining = max(self._remaining - tokens(line) - 1, 0)
        self._lines.append(line)

    def text(self) -> str:
        return "\n".join(self._lines).rstrip()


def build(snapshot: Snapshot, findings: list[Finding], topics: Iterable[Topic], question: str | None,
          budget_tokens: int, docs: Iterable[Any] = ()) -> str:
    """El contexto para esta pregunta, de como mucho `budget_tokens` tokens. `docs` son trozos con `title` y `text`."""
    topics = set(topics)
    lines = Lines(budget_tokens)
    asked = normalize(question)

    if Topic.ACTION in topics:
        return _action(findings, lines)
    lines.add("CONCLUSIONES:")
    relevant = [finding for finding in findings
                if finding["severity"] == "CRITICAL" or finding["topic"] in topics][:MAX_FINDINGS]
    if not relevant:
        lines.add("- No se ha detectado ningún problema en lo que preguntas.")
    for finding in relevant:
        lines.add("- " + finding["text"])

    lines.add(f"DATOS (a las {snapshot['time'][11:19]} UTC):")
    if Topic.STATE in topics or not snapshot["broker"]["available"]:
        state(snapshot, lines)
    if Topic.TRACE in topics:
        trace(snapshot, lines)
    if Topic.QUEUES in topics:
        queues(snapshot, lines, asked, " ".join(finding["text"] for finding in relevant))
    if Topic.EXCHANGES in topics:
        exchanges(snapshot, lines, asked)
    if Topic.EMITTERS in topics:
        emitters(snapshot, lines, asked)
    if Topic.CONSUMERS in topics:
        consumers(snapshot, lines, asked)
    if Topic.FLOW in topics:
        flow(snapshot, lines)

    cards = knowledge.find(question, MAX_CARDS_FOR_CONCEPTS if Topic.CONCEPT in topics else 1)
    if cards:
        lines.add("MANUAL:")
        for card in cards:
            lines.add(f"- «{card.title}»: {card.text}")
    documentation(docs, lines)
    return lines.text()


def _action(findings: list[Finding], lines: Lines) -> str:
    """
    Una petición de cambio: el asistente no la hace, así que solo necesita saber dónde se hace. Los datos del
    laboratorio sobran y distraerían al modelo; solo se añade lo que impide usarlo.
    """
    lines.add("PETICIÓN: el usuario pide hacer un cambio. NO PUEDES HACERLO: dile dónde se hace.")
    lines.add("MANUAL:")
    actions = knowledge.card(knowledge.ACTIONS)
    lines.add(f"- «{actions.title}»: {actions.text}")
    for finding in findings:
        if finding["severity"] == "CRITICAL":
            lines.add("- " + finding["text"])
    return lines.text()


def state(snapshot: Snapshot, lines: Lines) -> None:
    broker = snapshot["broker"]
    lines.add(f"- RabbitMQ: levantado (versión {broker.get('version')})" if broker["available"]
              else "- RabbitMQ: no disponible")
    lines.add(f"- Hay {len(snapshot['queues'])} colas, {len(snapshot['exchanges'])} exchanges, "
              f"{len(snapshot['emitters'])} emisores y {len(snapshot['consumers'])} consumidores simulados.")


def trace(snapshot: Snapshot, lines: Lines) -> None:
    lines.add("- Trazado de RabbitMQ: " + {"ACTIVE": "activo (tracing: true)",
                                           "DISABLED": "desactivado (tracing: false)"}.get(
        snapshot["trace"]["state"], "no se puede comprobar"))


def queues(snapshot: Snapshot, lines: Lines, asked: str = "", conclusions: str = "") -> None:
    """Las colas de las que se habla primero, después las que tienen conclusiones y por último las más llenas."""
    conclusions = normalize(conclusions)
    ordered = sorted(snapshot["queues"], key=lambda queue: (not _mentioned(asked, queue["name"]),
                                                            not _mentioned(conclusions, queue["name"]),
                                                            -queue["ready"]))
    if not ordered:
        lines.add("- No hay colas.")
    written = 0
    for queue in ordered:
        line = (f"- cola «{queue['name']}»: {queue['ready']} listos, {queue['unacknowledged']} pendientes de "
                "confirmar")
        if queue["consumers"] >= 0:
            line += f", {queue['consumers']} {'consumidor' if queue['consumers'] == 1 else 'consumidores'}"
        if queue.get("inPerSecond") is not None and queue.get("outPerSecond") is not None:
            line += f", entran {number(queue['inPerSecond'])}/s y salen {number(queue['outPerSecond'])}/s"
        if queue.get("deadLetterQueue"):
            line += f", su dead-letter queue es «{queue['deadLetterQueue']}»"
        if not lines.add(line):
            break
        written += 1
    if written < len(ordered):
        lines.add_always(f"- (y {len(ordered) - written} colas más, sin detalle por falta de espacio)")


def exchanges(snapshot: Snapshot, lines: Lines, asked: str = "") -> None:
    ordered = sorted(snapshot["exchanges"], key=lambda exchange: not _mentioned(asked, exchange["name"]))
    if not ordered:
        lines.add("- No hay exchanges creados.")
    written = 0
    for exchange in ordered:
        bound = ("enlazado a " + ", ".join(f"«{name}»" for name in exchange["boundQueues"])
                 if exchange["boundQueues"] else "sin bindings")
        if not lines.add(f"- exchange «{exchange['name']}» ({exchange['type']}): {bound}"):
            break
        written += 1
    if written < len(ordered):
        lines.add_always(f"- (y {len(ordered) - written} exchanges más)")


def emitters(snapshot: Snapshot, lines: Lines, asked: str = "") -> None:
    running = sum(1 for emitter in snapshot["emitters"] if emitter["status"] == "RUNNING")
    load = snapshot["load"]
    lines.add(f"- Emisores en marcha: {running} de {len(snapshot['emitters'])}. Suman "
              f"{number(load['ratePerSecondInUse'])} mensajes por segundo (límite {load['maxRatePerSecond']}).")
    for emitter in sorted(snapshot["emitters"], key=lambda emitter: not _mentioned(asked, emitter["name"])):
        line = (f"- emisor «{emitter['name']}»: {_STATUS.get(emitter['status'], emitter['status'])}, publica en "
                f"{emitter['destination']}, {number(emitter['ratePerSecond'])} por segundo, {emitter['published']} "
                f"publicados, {emitter['unrouted']} sin enrutar")
        if not lines.add(line):
            break


def consumers(snapshot: Snapshot, lines: Lines, asked: str = "") -> None:
    ordered = sorted(snapshot["consumers"], key=lambda consumer: not _mentioned(asked, consumer["name"]))
    if not ordered:
        lines.add("- No hay consumidores simulados.")
    for consumer in ordered:
        line = (f"- consumidor simulado «{consumer['name']}»: {_STATUS.get(consumer['status'], consumer['status'])}, "
                f"lee de la cola «{consumer['queue']}», {number(consumer['ratePerSecond'])} por segundo, "
                f"{consumer['processed']} procesados, {consumer['failed']} fallidos")
        if not lines.add(line):
            break


def flow(snapshot: Snapshot, lines: Lines) -> None:
    load = snapshot["load"]
    lines.add(f"- Flujo: los emisores en marcha suman {number(load['ratePerSecondInUse'])} mensajes por segundo; "
              f"se omiten {load['omittedPerSecond']} eventos por segundo en la animación.")


def documentation(docs: Iterable[Any], lines: Lines) -> None:
    docs = list(docs)
    if not docs:
        return
    lines.add("DOCUMENTACIÓN:")
    for doc in docs:
        text = doc.text if len(doc.text) <= MAX_DOC_CHARS else doc.text[:MAX_DOC_CHARS].rsplit(" ", 1)[0] + "…"
        lines.add(f"- «{doc.title}»: {' '.join(text.split())}")


def _mentioned(normalized_text: str, name: str) -> bool:
    return normalize(name) in normalized_text
