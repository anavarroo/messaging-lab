from contextlib import aclosing

import pytest

from app import service as assistant
from app.classifier import GENERAL, classify
from app.errors import AssistantError, BusyError, UnavailableError, ValidationError
from app.service import Turn
from tests.fakes import finding, queue, snapshot

DOWN = finding("CAUSA: RabbitMQ no está disponible (sin respuesta). SOLUCIÓN: levántalo.", "CRITICAL", "STATE")


async def ask(service, question, history=None) -> str:
    pending = await service.begin(question, history)
    async with aclosing(pending.run()) as tokens:
        return "".join([token async for token in tokens])


async def test_the_general_state_is_answered_without_the_model(service, backend, llm):
    backend.findings = [finding("CAUSA: la cola «barra» no tiene consumidor"), finding("Info", "INFO")]
    assert await ask(service, "¿Está todo bien?") == ("He detectado 2 cosas que revisar:\n"
                                                      "- CAUSA: la cola «barra» no tiene consumidor\n- Info")
    assert llm.calls == []


async def test_the_general_state_without_problems(service, llm):
    assert await ask(service, "¿Hay algún problema?") == (
        "No he detectado ningún problema: RabbitMQ está levantado y no hay colas atascadas, emisores fallados ni "
        "mensajes sin enrutar.")
    assert llm.calls == []


async def test_the_activity_is_answered_without_the_model(service, backend, llm):
    backend.activity, backend.active = "Ahora mismo: el emisor «Brazo» publica 10 mensajes por segundo.", True
    assert await ask(service, "¿Hay algo pasando datos?") == backend.activity
    assert llm.calls == []


async def test_with_rabbitmq_down_any_lab_question_gets_the_cause_without_the_model(service, backend, llm):
    backend.lab, backend.findings = snapshot(available=False), [DOWN]
    for question in ("¿Hay algo pasando datos?", "¿Cómo va la cola cocina?", "¿Está todo bien?"):
        assert await ask(service, question) == DOWN["text"]
    assert llm.calls == []


async def test_with_rabbitmq_down_a_concept_still_goes_to_the_model(service, backend, llm):
    backend.lab, backend.findings = snapshot(available=False), [DOWN]
    llm.rounds = [["Un exchange reparte mensajes."]]
    assert await ask(service, "¿Qué es un exchange?") == "Un exchange reparte mensajes."
    assert DOWN["text"] in llm.calls[0]["messages"][-1]["content"]


async def test_other_questions_go_to_the_model_with_the_prepared_context(service, backend, llm):
    backend.lab = snapshot(queues=[queue("cocina", 480, 1, 11.0, 5.0)])
    llm.rounds = [["Crece ", "porque entran más."]]
    assert await ask(service, "¿Por qué crece la cola cocina?") == "Crece porque entran más."
    messages = llm.calls[0]["messages"]
    assert messages[0]["role"] == "system" and messages[0]["content"].startswith(assistant.INSTRUCTIONS)
    assert "Funcionas con el modelo «qwen2.5:3b» y buscas en la documentación con «bge-m3»" in messages[0]["content"]
    assert messages[-1]["content"].startswith("CONTEXTO:\nCONCLUSIONES:")
    assert "- cola «cocina»: 480 listos" in messages[-1]["content"]
    assert messages[-1]["content"].endswith("PREGUNTA: ¿Por qué crece la cola cocina?")


async def test_keeps_the_history_that_fits(service, llm):
    history = [Turn("USER", "a" * 3000), Turn("ASSISTANT", "vieja"), Turn("USER", "¿Y la cola?"), Turn("ASSISTANT", "Bien.")]
    llm.rounds = [["Vale."]]
    await ask(service, "¿Qué es un binding?", history)
    messages = llm.calls[0]["messages"]
    assert [message["role"] for message in messages] == ["system", "assistant", "user", "assistant", "user"]
    assert messages[1]["content"] == "vieja"


@pytest.mark.parametrize("question, message", [
    (None, "Escribe una pregunta."), ("   ", "Escribe una pregunta."),
    ("x" * 501, "La pregunta no puede superar los 500 caracteres."),
])
async def test_rejects_empty_or_too_long_questions(service, question, message):
    with pytest.raises(ValidationError, match=message):
        await service.begin(question)


async def test_only_one_question_at_a_time(service, llm):
    llm.rounds = [["uno"], ["dos"]]
    pending = await service.begin("¿Qué es un binding?")
    with pytest.raises(BusyError, match="ocupado"):
        await service.begin("¿Y una cola?")
    async with aclosing(pending.run()) as tokens:
        [token async for token in tokens]
    assert await ask(service, "¿Y una cola?") == "dos"


async def test_a_question_that_was_never_answered_frees_the_assistant_after_the_timeout(service, clock):
    await service.begin("¿Qué es un binding?")
    clock.now += assistant.TIMEOUT_SECONDS + 31
    await service.begin("¿Y una cola?")


async def test_a_failed_check_frees_the_assistant(service, backend, llm):
    backend.down = True
    with pytest.raises(UnavailableError, match="backend no responde"):
        await service.begin("¿Qué es un binding?")
    backend.down = False
    service._status = None
    llm.rounds = [["sí"]]
    assert await ask(service, "¿Qué es un binding?") == "sí"


async def test_unavailable_when_the_model_is_not_ready(service, llm):
    llm.models_available = {"bge-m3:latest"}
    with pytest.raises(UnavailableError, match="todavía no está descargado"):
        await service.begin("¿Qué es un binding?")


async def test_stops_when_the_answer_takes_too_long(service, llm, clock):
    llm.rounds = [["uno ", "dos ", "tres"]]

    def tick():
        clock.now += 70

    llm.on_token = tick
    pending = await service.begin("¿Qué es un binding?")
    received = []
    with pytest.raises(AssistantError, match="tardado demasiado"):
        async with aclosing(pending.run()) as tokens:
            async for token in tokens:
                received.append(token)
    assert received == ["uno "]
    assert llm.closed == 1


async def test_a_concept_question_searches_the_documentation(service, index, llm):
    from app.docs import Chunk
    await index.build([Chunk("Guía", "Guía › Binding", "Un binding une un exchange con una cola.")])
    llm.rounds = [["Es una regla."]]
    await ask(service, "¿Qué es un binding?")
    assert "DOCUMENTACIÓN:\n- «Guía › Binding»" in llm.calls[-1]["messages"][-1]["content"]


def test_a_general_question_without_a_status_check_goes_to_the_model():
    assert classify("Hola") == GENERAL
    assert assistant.direct_answer({"brokerAvailable": True, "findings": []}, GENERAL, False) is None


async def test_an_action_request_says_it_cannot_and_where_it_is_done_without_the_model(service, llm):
    answer = await ask(service, "Borra la cola barra")
    assert answer == ("No puedo hacerlo: soy de solo lectura y no puedo hacer cambios en el laboratorio. Puedes hacerlo tú:"
                      "\n- Crear o eliminar colas, y vaciarlas: «Colas».")
    assert "«Parar todo» en «Generador»" in await ask(service, "Para todos los emisores ahora mismo")
    assert "«Publicación»" in await ask(service, "Publica un mensaje en la cola cocina")
    assert "«Publicación»" not in await ask(service, "Para todos los emisores")
    assert llm.calls == []


async def test_an_action_request_with_rabbitmq_down_explains_that_first(service, backend):
    backend.lab, backend.findings = snapshot(available=False), [DOWN]
    assert await ask(service, "Borra la cola barra") == DOWN["text"]


async def test_every_question_for_the_model_gets_the_closest_documentation(service, index, llm):
    from app.docs import Chunk
    await index.build([Chunk("Guía", f"Guía › {i}", f"texto {i}") for i in range(5)])
    llm.rounds = [["a"], ["b"]]
    await ask(service, "¿Qué es un binding?")
    await ask(service, "¿Qué pasa si dos consumidores leen de la misma cola?")
    assert llm.calls[0]["messages"][-1]["content"].count("- «Guía › ") == 3
    assert llm.calls[1]["messages"][-1]["content"].count("- «Guía › ") == 1


@pytest.mark.parametrize("question, card", [
    ("¿Con qué tecnologías y versiones está hecho el proyecto?", "Tecnologías y versiones"),
    ("¿Cómo está organizado el proyecto? ¿Qué arquitectura tiene?", "Arquitectura"),
])
async def test_questions_about_the_project_go_to_the_model_with_its_card_and_without_lab_data(service, backend, llm,
                                                                                            question, card):
    backend.lab = snapshot(queues=[queue("cocina", 480, 1, 11.0, 5.0)])
    llm.rounds = [["Respuesta."]]
    assert await ask(service, question) == "Respuesta."
    content = llm.calls[0]["messages"][-1]["content"]
    assert f"«{card}»" in content
    assert "- cola «" not in content


async def test_asking_to_change_a_port_or_the_model_explains_how_without_the_model(service, llm):
    answer = await ask(service, "Cambia el puerto del backend a 9090")
    assert answer.startswith("No puedo hacerlo") and "BACKEND_PORT" in answer and "infra/.env" in answer
    assert "ASSISTANT_MODEL" in await ask(service, "Cambia el modelo a uno más ligero")
    assert "«Estado»" in await ask(service, "Reinicia RabbitMQ")
    assert llm.calls == []


def test_the_manual_has_the_technical_and_how_to_cards():
    from app import knowledge
    texts = {card.id: card.text for card in knowledge.CARDS}
    assert "Spring Boot 4.1.2" in texts["tecnologias"] and "Angular 21" in texts["tecnologias"]
    assert "docker-proxy" in texts["arquitectura"]
    assert "OLLAMA_PORT" in texts["puertos"] and "qwen2.5:1.5b" in texts["modelo"]


def test_a_project_card_wins_a_tie_but_not_a_clearer_lab_card():
    from app import knowledge
    # «backend» contiene «ack» (confirmaciones): empata con «puerto» y gana la ficha del proyecto
    assert knowledge.best_of("¿Cómo cambio el puerto del backend?", knowledge.ABOUT_PROJECT).id == "puertos"
    assert knowledge.best_of("¿Qué es un ack y cómo se confirma con ack?", knowledge.ABOUT_PROJECT) is None



@pytest.mark.parametrize("question, expected", [
    ("¿Cómo reinicio RabbitMQ desde la terminal?", "restart rabbitmq"),
    ("¿Cómo cambio el puerto del frontend?", "FRONTEND_PORT"),
    ("Quiero usar un modelo más liviano, ¿cómo lo hago?", "ASSISTANT_MODEL"),
    ("¿Puedo usar un modelo más potente?", "qwen2.5:7b"),
])
async def test_how_to_change_ports_or_the_model_or_restart_is_the_exact_card_without_the_model(service, llm, question,
                                                                                             expected):
    assert expected in await ask(service, question)
    assert llm.calls == []


async def test_asking_how_the_model_is_does_not_get_the_how_to_card(service, llm):
    llm.rounds = [["Listo."]]
    assert await ask(service, "¿El modelo está listo?") == "Listo."



@pytest.mark.parametrize("question, card", [
    ("¿Cómo funcionas?", "Cómo funciona el asistente"),
    ("¿De dónde sacas la información?", "De dónde saca la información el asistente"),
    ("¿Guardas mis preguntas? ¿Usas internet?", "De dónde saca la información el asistente"),
    ("¿Cómo está levantado el asistente?", "Cómo está levantado el asistente"),
    ("¿Te puedes equivocar?", "Límites del asistente"),
    ("¿Por qué tardas tanto en contestar?", "Si el asistente no responde o va lento"),
])
async def test_questions_about_the_assistant_get_their_card_without_lab_data(service, backend, llm, question, card):
    backend.lab = snapshot(queues=[queue("cocina", 480, 1, 11.0, 5.0)])
    llm.rounds = [["Respuesta."]]
    await ask(service, question)
    content = llm.calls[0]["messages"][-1]["content"]
    assert f"«{card}»" in content and "- cola «" not in content


def test_lab_questions_do_not_get_the_assistant_cards():
    from app import knowledge
    for question in ("¿Está levantado RabbitMQ?", "¿Por qué RabbitMQ no responde?", "¿Qué información tiene la cola cocina?",
                     "¿Por qué tarda en vaciarse la cola?", "¿Cuál es el límite del generador?"):
        assert knowledge.best_of(question, knowledge.ABOUT_PROJECT) is None, question


async def test_which_sections_the_app_has_is_the_exact_list_but_an_explanation_goes_to_the_model(service, llm):
    answer = await ask(service, "¿Qué apartados tiene el laboratorio de RabbitMQ?")
    assert "ocho pestañas" in answer and "«Flujo»" in answer and "«Laboratorio Kafka» (todavía no disponible" in answer
    assert llm.calls == []
    llm.rounds = [["Explicación."]]
    assert await ask(service, "Explícame qué es este proyecto, qué es RabbitMQ y todos los apartados") == "Explicación."
