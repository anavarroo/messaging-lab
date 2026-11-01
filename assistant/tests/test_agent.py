from contextlib import aclosing

from app import agent
from app.docs import DocIndex
from app.errors import ToolsNotSupportedError
from app.llm import ToolCall
from app.tools import Toolbox
from tests.fakes import FakeBackend, FakeLlm, queue, snapshot

QUESTION = [{"role": "system", "content": "instrucciones"}, {"role": "user", "content": "CONTEXTO:\n...\n\nPREGUNTA: ¿y?"}]


def toolbox(backend=None) -> Toolbox:
    backend = backend or FakeBackend(snapshot(queues=[queue("cocina", 40)]), activity="Se mueven 3 mensajes por segundo.")
    return Toolbox(backend.client(), DocIndex(FakeLlm(), "bge-m3"))


async def answer(llm, tools=None) -> str:
    async with aclosing(agent.answer(llm, tools or toolbox(), QUESTION)) as tokens:
        return "".join([token async for token in tokens])


async def test_answers_straight_away_with_the_prepared_context_and_offers_the_tools():
    llm = FakeLlm(["Todo ", "bien."])
    assert await answer(llm) == "Todo bien."
    assert len(llm.calls) == 1 and llm.calls[0]["tools"]
    assert llm.calls[0]["messages"] == QUESTION


async def test_runs_the_tool_the_model_asks_for_and_gives_it_the_result():
    llm = FakeLlm([ToolCall("colas", {"nombre": "cocina"})], ["La cola tiene 40."])
    assert await answer(llm) == "La cola tiene 40."
    second = llm.calls[1]["messages"]
    assert second[-2]["tool_calls"][0]["function"] == {"name": "colas", "arguments": {"nombre": "cocina"}}
    assert second[-1] == {"role": "tool", "tool_name": "colas",
                          "content": "- cola «cocina»: 40 listos, 0 pendientes de confirmar, 1 consumidor"}


async def test_chains_several_tools():
    llm = FakeLlm([ToolCall("colas")], [ToolCall("actividad"), ToolCall("diagnostico")], ["Hecho."])
    assert await answer(llm) == "Hecho."
    assert [message.get("tool_name") for message in llm.calls[2]["messages"] if message["role"] == "tool"] == [
        "colas", "actividad", "diagnostico"]


async def test_never_more_than_four_tool_calls_and_then_it_must_answer():
    llm = FakeLlm([ToolCall("colas"), ToolCall("colas"), ToolCall("colas")], [ToolCall("colas"), ToolCall("colas")],
                  ["Respuesta."])
    assert await answer(llm) == "Respuesta."
    assert sum(1 for message in llm.calls[-1]["messages"] if message["role"] == "tool") == agent.MAX_TOOL_CALLS
    assert llm.calls[-1]["tools"] is None


async def test_falls_back_to_the_prepared_context_if_the_model_cannot_use_tools():
    llm = FakeLlm(ToolsNotSupportedError("no"), ["Con el contexto."])
    assert await answer(llm) == "Con el contexto."
    assert llm.calls[1] == {"messages": QUESTION, "tools": None}


async def test_falls_back_if_the_model_says_nothing_after_the_tools():
    llm = FakeLlm([ToolCall("colas")], [], ["Con el contexto."])
    assert await answer(llm) == "Con el contexto."
    assert llm.calls[-1] == {"messages": QUESTION, "tools": None}


async def test_closing_the_answer_closes_the_model_stream():
    llm = FakeLlm(["uno ", "dos ", "tres"])
    async with aclosing(agent.answer(llm, toolbox(), QUESTION)) as tokens:
        assert await anext(tokens) == "uno "
    assert llm.closed == 1
