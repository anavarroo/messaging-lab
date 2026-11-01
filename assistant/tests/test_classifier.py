import pytest

from app.classifier import GENERAL, Topic, classify, is_activity_check, is_status_check


@pytest.mark.parametrize("question, expected", [
    ("¿Por qué crece la cola cocina?", "QUEUES"),
    ("¿Qué es un binding?", "CONCEPT,EXCHANGES"),
    ("Explícame la diferencia entre direct y topic", "CONCEPT,EXCHANGES"),
    ("¿Mi emisor está publicando?", "EMITTERS"),
    ("¿Por qué no veo nada en la pestaña Flujo?", "FLOW,TRACE"),
    ("¿Está activo el trazado?", "TRACE"),
    ("¿Funciona RabbitMQ? ¿Qué versión tiene?", "STATE"),
    ("¿El backend está disponible?", "STATE"),
    ("¿Cómo confirmo un mensaje?", "CONSUMERS"),
    ("¿Qué consumidor lee de la cola cocina?", "CONSUMERS,QUEUES"),
    ("¿Cuántos mensajes por segundo entran?", "FLOW"),
    ("¿Cuál es el límite del generador?", "EMITTERS"),
    ("¿Dónde veo los mensajes rechazados?", "CONSUMERS"),
    ("¿Qué hay en la dead-letter queue?", "QUEUES"),
    ("¿Por qué hay mensajes sin enrutar?", "EMITTERS,EXCHANGES"),
    ("Para todos los emisores ahora mismo.", "ACTION,EMITTERS"),
    ("Borra la cola barra.", "ACTION,QUEUES"),
    ("¿Puedes vaciar la cola cocina?", "ACTION,QUEUES"),
    ("Quiero que pares el generador", "ACTION,EMITTERS"),
])
def test_assigns_the_topics_of_the_question(question, expected):
    assert {Topic(topic) for topic in expected.split(",")} <= classify(question)


def test_a_question_is_not_mistaken_for_an_action():
    for question in ("¿Para qué sirve un binding?", "¿Por qué crece la cola?", "¿Qué es parar un emisor?"):
        assert Topic.ACTION not in classify(question), question


def test_recognises_questions_about_the_general_state():
    for question in ("¿Está todo bien?", "¿Hay algún problema?", "¿Qué pasa?", "¿Cómo va el laboratorio?",
                     "Dame un resumen"):
        assert is_status_check(question), question
    for question in ("¿Qué tiempo hace hoy en Cádiz?", "Hola", "", None):
        assert not is_status_check(question), question


def test_recognises_questions_about_whether_data_is_flowing():
    for question in ("¿Hay algo pasando datos?", "¿Hay tráfico?", "¿Se mueve algo?", "¿Hay actividad ahora?",
                     "¿Se está publicando algo?"):
        assert is_activity_check(question), question
    assert not is_activity_check("¿Qué es un binding?")


def test_ignores_case_and_accents():
    assert Topic.TRACE in classify("traZADO")
    assert {Topic.CONCEPT, Topic.EXCHANGES} <= classify("¿QUÉ ES UN EXCHANGE?")


def test_a_question_that_matches_nothing_or_is_empty_gets_a_general_summary():
    for question in ("¿Todo bien?", "", "   ", None):
        assert classify(question) == GENERAL


def test_a_question_can_be_about_several_things():
    assert {Topic.EMITTERS, Topic.QUEUES, Topic.FLOW} <= classify("¿Por qué mi emisor no llena la cola y no veo el flujo?")
