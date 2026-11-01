"""
El agente, en modo híbrido (resultado de la prueba técnica T01): el servicio ya le pasa al modelo el contexto
preparado para la pregunta y, si le falta algo, el modelo puede pedir más herramientas, como mucho 4 por respuesta. Si
el modelo no sabe usar herramientas, o no contesta nada tras usarlas, responde con el contexto preparado sin ellas.
"""

from collections.abc import AsyncIterator
from contextlib import aclosing
from typing import Any

from app.errors import ToolsNotSupportedError
from app.llm import Llm
from app.tools import DEFINITIONS, Toolbox

MAX_TOOL_CALLS = 4


async def answer(llm: Llm, toolbox: Toolbox, messages: list[dict[str, Any]]) -> AsyncIterator[str]:
    """La respuesta por trozos. `messages` acaba con la pregunta del usuario y su contexto preparado."""
    conversation = list(messages)
    calls = 0
    while True:
        offer_tools = calls < MAX_TOOL_CALLS
        requested: list = []
        answered = False
        try:
            # aclosing: si se cancela la pregunta, se cierra la conexión con el modelo en el acto
            async with aclosing(llm.chat(conversation, DEFINITIONS if offer_tools else None)) as chunks:
                async for chunk in chunks:
                    if chunk.content:
                        answered = True
                        yield chunk.content
                    requested += chunk.tool_calls
        except ToolsNotSupportedError:
            if answered:
                raise
            async with aclosing(_without_tools(llm, messages)) as tokens:
                async for token in tokens:
                    yield token
            return
        if answered or not requested:
            if not answered:
                # el modelo se ha quedado callado: se le pregunta otra vez, solo con el contexto preparado
                async with aclosing(_without_tools(llm, messages)) as tokens:
                    async for token in tokens:
                        yield token
            return
        allowed = requested[:MAX_TOOL_CALLS - calls]
        conversation.append({"role": "assistant", "content": "",
                             "tool_calls": [{"function": {"name": call.name, "arguments": call.arguments}}
                                            for call in allowed]})
        for call in allowed:
            result = await toolbox.run(call.name, call.arguments)
            conversation.append({"role": "tool", "tool_name": call.name, "content": result})
        calls += len(allowed)


async def _without_tools(llm: Llm, messages: list[dict[str, Any]]) -> AsyncIterator[str]:
    async with aclosing(llm.chat(messages, None)) as chunks:
        async for chunk in chunks:
            if chunk.content:
                yield chunk.content

