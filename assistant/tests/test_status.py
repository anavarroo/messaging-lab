from app import backend as lab
from app import llm as model
from app.docs import IndexState
from app.service import STATUS_CACHE_SECONDS, State


async def test_ready_when_ollama_both_models_the_index_and_the_backend_are(service):
    status = await service.status()
    assert (status.state, status.message) == (State.READY, None)


async def test_unavailable_when_ollama_does_not_answer(service, llm):
    llm.down = True
    status = await service.status()
    assert (status.state, status.message) == (State.UNAVAILABLE, model.UNAVAILABLE)
    assert "OLLAMA_REPLICAS=0" in status.message


async def test_preparing_while_the_chat_model_downloads(service, llm):
    llm.models_available = {"bge-m3:latest"}
    status = await service.status()
    assert status.state == State.PREPARING
    assert status.message.startswith("El modelo «qwen2.5:3b» todavía no está descargado")


async def test_preparing_while_the_embedding_model_downloads(service, llm):
    llm.models_available = {"qwen2.5:3b"}
    status = await service.status()
    assert status.state == State.PREPARING and "«bge-m3»" in status.message


async def test_preparing_while_the_documentation_is_indexed(service, index):
    index.state = IndexState.BUILDING
    status = await service.status()
    assert status.state == State.PREPARING and "documentación" in status.message


async def test_a_failed_index_does_not_block_the_assistant(service, index):
    index.state = IndexState.FAILED
    assert (await service.status()).state == State.READY


async def test_unavailable_when_the_backend_does_not_answer(service, backend):
    backend.down = True
    status = await service.status()
    assert (status.state, status.message) == (State.UNAVAILABLE, lab.UNAVAILABLE)


async def test_the_status_is_remembered_for_a_few_seconds(service, llm, clock):
    await service.status()
    llm.down = True
    assert (await service.status()).state == State.READY
    clock.now += STATUS_CACHE_SECONDS + 1
    assert (await service.status()).state == State.UNAVAILABLE
