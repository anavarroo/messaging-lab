"""
Decide de qué trata una pregunta, para adelantarle al modelo solo los datos que hacen falta. Es deliberadamente
simple (palabras clave): determinista, explicable y sin gastar memoria del modelo. Portado de la especificación 06.
"""

import re
from enum import StrEnum

from app.words import normalize


class Topic(StrEnum):
    """Los mismos temas que usa el backend en sus conclusiones (`LabFinding.topic`)."""

    STATE = "STATE"
    TRACE = "TRACE"
    FLOW = "FLOW"
    QUEUES = "QUEUES"
    EXCHANGES = "EXCHANGES"
    EMITTERS = "EMITTERS"
    CONSUMERS = "CONSUMERS"
    CONCEPT = "CONCEPT"
    ACTION = "ACTION"


# fragmentos de palabra, ya sin tildes, que indican cada tema
KEYWORDS: dict[Topic, tuple[str, ...]] = {
    Topic.TRACE: ("traz", "tracing", "trace", "firehose"),
    Topic.FLOW: ("flujo", "pasando", "trafico", "actividad", "moviendo", "fluyendo", "tiempo real", "diagrama",
                 "animacion", "ritmo", "por segundo", "omit", "grafica", "punto"),
    Topic.QUEUES: ("cola", "listos", "acumul", "crece", "vaci", "pendiente", "dead-letter", "dead letter", "dlq",
                   "fallidos", "atasc"),
    Topic.EXCHANGES: ("exchange", "binding", "routing", "enrut", "topic", "fanout", "direct", "patron"),
    Topic.EMITTERS: ("emisor", "enrut", "emite", "emiti", "generador", "publica", "plantilla", "limite", "sensor",
                     "robot"),
    Topic.CONSUMERS: ("consumidor", "consume", "consumir", "confirm", "rechaz", "ack", "reencol", "reentreg"),
    Topic.STATE: ("estado", "funciona", "levantado", "caido", "disponible", "conectado", "version", "backend",
                  "rabbitmq esta", "no responde", "modelo"),
    Topic.CONCEPT: ("que es", "que son", "significa", "para que sirve", "como funciona", "explica", "diferencia",
                    "que hace", "defin"),
}

# los temas de una pregunta general («¿está todo bien?»): un resumen de todo
GENERAL = frozenset({Topic.STATE, Topic.QUEUES, Topic.EMITTERS, Topic.CONSUMERS})

_STATUS_CHECK = re.compile(
    "todo bien|todo ok|todo correcto|algun problema|hay problemas|algo mal|algo raro|que pasa|que ocurre|como va|"
    "como esta|resumen|estado del laboratorio")

_ACTIVITY_CHECK = re.compile(
    "pasando|trafico|actividad|moviendo|fluyendo|se mueve|circulando|algo funcionando|hay flujo|algo en marcha|"
    "esta publicando|se esta publicando")

# una petición de acción: un verbo de cambio al principio, o tras «puedes», «podrías» o «quiero que»
_ACTION = re.compile(
    r"(^|puedes |podrias |quiero que |hazme |por favor )(para(?! que)|parar|pares|pausa|deten|detengas|borra|borrar|"
    r"borres|elimina|eliminar|elimines|crea|crear|crees|vacia|vaciar|vacies|inicia|iniciar|inicies|arranca|publica|"
    r"publicar|publiques|cambia|cambiar|cambies|sube|baja|reinicia|activa|actives|desactiva|desactives)\b")


def is_status_check(question: str | None) -> bool:
    """¿Pregunta por cómo está el laboratorio en general («¿está todo bien?», «¿hay algún problema?»)?"""
    return question is not None and _STATUS_CHECK.search(normalize(question)) is not None


def is_activity_check(question: str | None) -> bool:
    """¿Pregunta si ahora mismo se mueven mensajes («¿hay algo pasando datos?», «¿hay tráfico?»)?"""
    return question is not None and _ACTIVITY_CHECK.search(normalize(question)) is not None


def classify(question: str | None) -> frozenset[Topic]:
    """Los temas de la pregunta; si no encaja en ninguno, un resumen general."""
    text = normalize(question)
    topics = {topic for topic, words in KEYWORDS.items() if any(word in text for word in words)}
    if _ACTION.search(re.sub(r"^[¿¡\s]+", "", text)):
        topics.add(Topic.ACTION)
    # el flujo se alimenta del trazado: si algo falla ahí, es lo primero que hay que mirar
    if Topic.FLOW in topics:
        topics.add(Topic.TRACE)
    return frozenset(topics) if topics else GENERAL
