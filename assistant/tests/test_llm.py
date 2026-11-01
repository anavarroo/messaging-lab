from types import SimpleNamespace

from app.llm import CUT, OllamaLlm


def part(content="", done=False, done_reason=None):
    return SimpleNamespace(message=SimpleNamespace(content=content, tool_calls=None), done=done, done_reason=done_reason)


async def answer(parts) -> str:
    llm = OllamaLlm("http://ollama", "qwen2.5:3b", "bge-m3", {})

    async def stream():
        for item in parts:
            yield item

    async def chat(**_):
        return stream()

    llm._client.chat = chat
    return "".join([chunk.content async for chunk in llm.chat([{"role": "user", "content": "¿y?"}])])


async def test_an_answer_cut_by_its_length_says_so():
    assert await answer([part("1. Backend"), part(" 2. Mod", done=True, done_reason="length")]) == "1. Backend 2. Mod" + CUT


async def test_a_complete_answer_has_no_warning():
    assert await answer([part("Hecho."), part(done=True, done_reason="stop")]) == "Hecho."
