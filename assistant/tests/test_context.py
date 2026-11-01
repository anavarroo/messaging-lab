import re

from app import context
from app.classifier import classify
from app.docs import Chunk
from tests.fakes import consumer, emitter, finding, queue, snapshot

GROWING = "CAUSA: la cola «cocina» crece porque entran 11 mensajes por segundo y salen 5"


def growing():
    return snapshot(queues=[queue("cocina", 480, 1, 11.0, 5.0), queue("barra", 3, 1, 0.0, 0.0)],
                    exchanges=[{"name": "pizzeria", "type": "topic", "boundQueues": ["cocina", "barra"]},
                               {"name": "avisos", "type": "fanout", "boundQueues": []}],
                    emitters=[emitter("Brazo robótico", rate=0.0)],
                    consumers=[consumer("lector-1", "cocina", rate=5.0)])


def build(lab, question, findings=(), budget=1500, docs=()):
    return context.build(lab, list(findings), classify(question), question, budget, docs)


def test_puts_the_conclusions_first_then_the_data():
    text = build(growing(), "¿Por qué crece la cola cocina?", [finding(GROWING)])
    assert text.startswith("CONCLUSIONES:")
    assert text.index("CONCLUSIONES:") < text.index("DATOS")
    assert GROWING in text


def test_gives_the_queue_data_for_a_queue_question():
    text = build(growing(), "¿Cómo va la cola cocina?")
    assert "- cola «cocina»: 480 listos, 0 pendientes de confirmar, 1 consumidor, entran 11/s y salen 5/s" in text
    assert "emisor «Brazo robótico»" not in text and "consumidor simulado" not in text


def test_an_emitter_question_gets_emitters_and_the_limit():
    text = build(growing(), "¿Qué hace mi emisor?")
    assert "Emisores en marcha: 1 de 1. Suman 0 mensajes por segundo (límite 100)" in text
    assert "- cola «" not in text


def test_a_consumer_question_gets_the_simulated_consumers():
    assert "consumidor simulado «lector-1»: en marcha, lee de la cola «cocina»" in build(growing(), "¿Qué consumidor lee?")


def test_an_exchange_question_shows_their_bindings_named_first():
    text = build(growing(), "¿Qué bindings tiene el exchange avisos?")
    assert "- exchange «pizzeria» (topic): enlazado a «cocina», «barra»" in text
    assert "«avisos» (fanout): sin bindings" in text
    assert text.index("avisos»") < text.index("pizzeria»")


def test_a_general_question_gets_a_summary_of_everything():
    text = build(growing(), "¿Todo bien?")
    assert "RabbitMQ: levantado (versión 4.4.0)" in text and "Hay 2 colas" in text and "- cola «cocina»" in text
    assert "DATOS (a las 10:00:00 UTC):" in text
    assert "MANUAL:" not in text


def test_says_so_when_there_is_nothing_wrong_with_what_is_asked():
    assert "No se ha detectado ningún problema en lo que preguntas." in build(growing(), "¿Cómo va la cola cocina?")


def test_a_broker_that_is_down_is_always_reported():
    down = snapshot(available=False)
    text = build(down, "¿Qué es un exchange?", [finding("CAUSA: RabbitMQ no está disponible (sin respuesta)", "CRITICAL", "STATE")])
    assert "CAUSA: RabbitMQ no está disponible (sin respuesta)" in text and "RabbitMQ: no disponible" in text


def test_a_trace_question_states_the_trace():
    assert "Trazado de RabbitMQ: desactivado (tracing: false)" in build(snapshot(trace="DISABLED"), "¿Está activo el trazado?")


def test_a_concept_question_includes_the_manual_cards_and_the_documentation():
    docs = [Chunk("Guía", "Guía › Binding", "Un binding es la regla que une un exchange con una cola.")]
    text = build(growing(), "¿Qué es un binding?", docs=docs)
    assert "MANUAL:" in text and "«Binding»: Un binding es la regla que une un exchange con una cola" in text
    assert "DOCUMENTACIÓN:\n- «Guía › Binding»: Un binding es la regla" in text


def test_long_documentation_is_cut():
    docs = [Chunk("README", "README › Largo", "palabra " * 500)]
    line = next(line for line in build(growing(), "¿Qué es?", docs=docs).splitlines() if "README › Largo" in line)
    assert len(line) < context.MAX_DOC_CHARS + 40 and line.endswith("…")


def test_an_action_request_says_where_it_is_done_and_nothing_else():
    text = build(growing(), "Borra la cola barra", [finding(GROWING)])
    assert text.startswith("PETICIÓN: el usuario pide hacer un cambio. NO PUEDES HACERLO")
    assert "«Dónde se hace cada cosa»" in text
    assert "CONCLUSIONES" not in text and "- cola «" not in text


def test_an_action_request_still_warns_when_rabbitmq_is_down():
    text = build(snapshot(available=False), "Borra la cola barra", [finding("CAUSA: RabbitMQ no está disponible", "CRITICAL", "STATE")])
    assert "CAUSA: RabbitMQ no está disponible" in text


def test_the_queue_the_question_names_comes_before_the_fuller_ones():
    lab = snapshot(queues=[queue("llena", 900), queue("pequeña", 1)])
    text = build(lab, "¿Cómo va la cola pequeña?")
    assert text.index("«pequeña»") < text.index("«llena»")


def test_never_exceeds_the_budget_and_says_how_many_queues_were_left_out():
    lab = snapshot(queues=[queue(f"cola-{i}", i, 1, 1.0, 1.0, dlq="fallidos") for i in range(300)])
    for budget in (600, 1000, 1500):
        assert context.tokens(build(lab, "¿Cómo van las colas?", budget=budget)) <= budget + 30
    assert re.search(r"\(y \d+ colas más, sin detalle por falta de espacio\)", build(lab, "¿Cómo van las colas?"))


def test_never_more_than_six_conclusions():
    findings = [finding(f"CAUSA: problema {i}") for i in range(10)]
    text = build(growing(), "¿Cómo van las colas?", findings)
    assert sum(1 for line in text.splitlines() if line.startswith("- CAUSA")) == context.MAX_FINDINGS


def test_flow_question_includes_the_rates_and_the_omitted():
    text = build(snapshot(rate=95.0, omitted=450), "¿Cuántos mensajes por segundo hay en el flujo?")
    assert "suman 95 mensajes por segundo; se omiten 450 eventos por segundo" in text


def test_token_estimate_is_conservative():
    assert (context.tokens(""), context.tokens("abc"), context.tokens("abcd")) == (0, 1, 2)


def test_a_question_about_the_project_gets_the_map_of_the_application():
    text = build(growing(), "Explícame qué es este proyecto y todos los apartados del laboratorio de rabbitmq")
    assert "«La aplicación y sus apartados»" in text
    assert "«Laboratorio Kafka» (todavía no disponible" in text and "ocho pestañas" in text
