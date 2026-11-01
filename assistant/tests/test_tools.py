from app.docs import Chunk, DocIndex, IndexState
from app.tools import DEFINITIONS, NAMES, Toolbox
from tests.fakes import FakeBackend, FakeLlm, consumer, emitter, finding, queue, snapshot


def toolbox(backend: FakeBackend, index=None) -> Toolbox:
    return Toolbox(backend.client(), index or DocIndex(FakeLlm(), "bge-m3"))


LAB = snapshot(queues=[queue("cocina", 40, 2, 10.0, 5.0, dlq="cocina-fallidos")],
               exchanges=[{"name": "pizzeria", "type": "topic", "boundQueues": ["cocina"]}],
               emitters=[emitter("Brazo", rate=10.0, published=120, unrouted=3)],
               consumers=[consumer("lector", "cocina", rate=5.0, processed=80, failed=1)], rate=10.0)


async def test_each_tool_returns_its_summary_in_spanish():
    tools = toolbox(FakeBackend(LAB, [finding("CAUSA: la cola «cocina» crece")], "el emisor «Brazo» publica 10 mensajes por segundo"))

    assert await tools.run("estado_laboratorio") == ("- RabbitMQ: levantado (versión 4.4.0)\n"
                                                     "- Hay 1 colas, 1 exchanges, 1 emisores y 1 consumidores simulados.\n"
                                                     "- Trazado de RabbitMQ: activo (tracing: true)")
    assert await tools.run("colas") == ("- cola «cocina»: 40 listos, 0 pendientes de confirmar, 2 consumidores, "
                                        "entran 10/s y salen 5/s, su dead-letter queue es «cocina-fallidos»")
    assert await tools.run("exchanges") == "- exchange «pizzeria» (topic): enlazado a «cocina»"
    generator = await tools.run("generador")
    assert "Emisores en marcha: 1 de 1. Suman 10 mensajes por segundo (límite 100)." in generator
    assert "- emisor «Brazo»: en marcha, publica en la cola «cocina», 10 por segundo, 120 publicados, 3 sin enrutar" in generator
    assert "- consumidor simulado «lector»: en marcha, lee de la cola «cocina», 5 por segundo, 80 procesados, 1 fallidos" in generator
    assert await tools.run("actividad") == "el emisor «Brazo» publica 10 mensajes por segundo"
    assert await tools.run("diagnostico") == "- CAUSA: la cola «cocina» crece"


async def test_the_tools_only_make_get_requests():
    backend = FakeBackend(LAB)
    tools = toolbox(backend)
    for name in NAMES:
        await tools.run(name, {"nombre": "cocina", "consulta": "cola"})
    assert backend.requests and all(method == "GET" for method, _ in backend.requests)
    assert {path for _, path in backend.requests} <= {"/api/lab/snapshot", "/api/lab/diagnostics", "/api/lab/activity"}


async def test_a_named_queue_comes_first():
    lab = snapshot(queues=[queue("llena", 900), queue("pequeña", 1)])
    assert (await toolbox(FakeBackend(lab)).run("colas", {"nombre": "Pequeña"})).startswith("- cola «pequeña»")


async def test_says_so_when_there_is_nothing():
    tools = toolbox(FakeBackend(snapshot()))
    assert await tools.run("colas") == "- No hay colas."
    assert await tools.run("exchanges") == "- No hay exchanges creados."
    assert await tools.run("diagnostico") == "No se ha detectado ningún problema."


async def test_a_backend_that_does_not_answer_is_explained_not_raised():
    backend = FakeBackend(LAB)
    backend.down = True
    assert "el backend no responde" in await toolbox(backend).run("colas")


async def test_an_unknown_tool_is_explained_and_unknown_arguments_ignored():
    tools = toolbox(FakeBackend(LAB))
    assert (await tools.run("borrar_cola")).startswith("No existe la herramienta «borrar_cola»")
    # los argumentos que no son de la herramienta se ignoran
    assert (await tools.run("actividad", {"nombre": "x"})) == "Nada se mueve."


async def test_documentation_search_returns_the_closest_chunks():
    llm = FakeLlm(vectors=lambda text: [1.0, 0.0] if "binding" in text.lower() else [0.0, 1.0])
    index = DocIndex(llm, "bge-m3")
    await index.build([Chunk("Guía", "Guía › Binding", "Un binding une un exchange con una cola."),
                       Chunk("Guía", "Guía › Cola", "Una cola guarda mensajes.")])
    text = await toolbox(FakeBackend(LAB), index).run("buscar_documentacion", {"consulta": "binding"})
    assert text.startswith("DOCUMENTACIÓN:\n- «Guía › Binding»")


async def test_documentation_search_while_the_index_is_being_prepared():
    index = DocIndex(FakeLlm(), "bge-m3")
    index.state = IndexState.BUILDING
    assert "se está preparando" in await toolbox(FakeBackend(LAB), index).run("buscar_documentacion", {"consulta": "x"})


def test_every_tool_is_described_for_the_model():
    assert {definition["function"]["name"] for definition in DEFINITIONS} == {
        "estado_laboratorio", "colas", "exchanges", "generador", "actividad", "diagnostico", "buscar_documentacion"}
    assert all(definition["function"]["description"] for definition in DEFINITIONS)
