import os
from pathlib import Path

import pytest

from app import docs
from app.docs import MAX_CHUNK_CHARS, Chunk, DocIndex, IndexState
from tests.fakes import FakeLlm

ROOT = Path(__file__).resolve().parents[2]


def test_markdown_is_cut_by_sections_with_the_heading_path_as_title():
    chunks = docs.markdown("# Título\nintro\n## Uno\ntexto uno\n### Uno a\ntexto a\n## Dos\ntexto dos\n", "README")
    assert [(chunk.title, chunk.text) for chunk in chunks] == [
        ("README › Título", "intro"), ("README › Uno", "texto uno"), ("README › Uno › Uno a", "texto a"),
        ("README › Dos", "texto dos")]


def test_a_long_section_is_split_without_exceeding_the_size():
    long = "\n\n".join(f"párrafo {i} " + "palabra " * 60 for i in range(20)) + "\n\n" + "x " * 2000
    chunks = docs.markdown("## Larga\n" + long, "README")
    assert len(chunks) > 1 and all(len(chunk.text) <= MAX_CHUNK_CHARS for chunk in chunks)
    assert {chunk.title for chunk in chunks} == {"README › Larga"}


def test_the_guide_is_cut_by_element_with_all_its_texts():
    source = ("export const ELEMENTS = [\n  {\n    id: 'binding',\n    name: 'Binding',\n"
              "    inOneSentence: 'Un binding es la regla que une un exchange con una cola.',\n"
              "    think:\n      'Es la dirección escrita en el sobre: l\\'etiqueta.',\n"
              "    links: [lab('Bindings', 'bindings')],\n  },\n];\n"
              "export const GLOSSARY = [\n  { term: 'Cola', definition: 'Donde esperan los mensajes.' },\n];\n")
    chunks = docs.guide_content(source)
    assert chunks == [
        Chunk("Guía", "Guía › Binding",
              "Binding\nUn binding es la regla que une un exchange con una cola.\nEs la dirección escrita en el sobre: l'etiqueta."),
        Chunk("Guía", "Guía › Cola", "Cola\nDonde esperan los mensajes.")]


def test_loads_the_readme_the_specs_and_the_guide_of_the_repository():
    chunks = docs.load(ROOT)
    sources = {chunk.source for chunk in chunks}
    assert {"README", "Guía", "Especificación 07-decoupled-assistant"} <= sources
    assert all(chunk.text and len(chunk.text) <= MAX_CHUNK_CHARS for chunk in chunks)


async def test_finds_the_closest_chunk_by_meaning():
    # vectores de juguete: dos «significados», sin que la pregunta comparta palabras con el trozo
    meaning = {"une un exchange": [1.0, 0.0], "regla de reparto": [0.9, 0.1], "esperan": [0.0, 1.0]}
    llm = FakeLlm(vectors=lambda text: next((v for k, v in meaning.items() if k in text), [0.5, 0.5]))
    index = DocIndex(llm, "bge-m3")
    await index.build([Chunk("Guía", "Guía › Cola", "Donde esperan los mensajes."),
                       Chunk("Guía", "Guía › Binding", "Un binding une un exchange con una cola.")])
    assert [chunk.title for chunk in await index.search("¿cuál es la regla de reparto?", 1)] == ["Guía › Binding"]


async def test_the_index_is_reused_from_disk_while_the_content_does_not_change(tmp_path):
    chunks = [Chunk("README", "README › A", "texto")]
    first = FakeLlm()
    await DocIndex(first, "bge-m3", tmp_path).build(chunks)
    again = FakeLlm()
    again.down = True  # si intentara calcular los embeddings, fallaría
    reused = DocIndex(again, "bge-m3", tmp_path)
    await reused.build(chunks)
    assert reused.ready
    changed = DocIndex(again, "bge-m3", tmp_path)
    with pytest.raises(Exception):
        await changed.build([Chunk("README", "README › A", "otro texto")])
    assert changed.state == IndexState.FAILED


async def test_waits_for_the_embedding_model_before_building():
    index = DocIndex(FakeLlm(), "bge-m3")
    answers = iter([False, False, True])

    async def ready():
        return next(answers)

    await index.keep_built([Chunk("README", "README › A", "texto")], ready, retry_seconds=0)
    assert index.ready


@pytest.mark.evaluation
@pytest.mark.parametrize("question, expected", [
    ("¿Adónde van a parar los mensajes descartados por quien los lee?", "Dead-letter queue"),
    ("¿Qué regla decide a qué buzones llega cada carta?", "Binding"),
    ("¿Cómo se reparten el trabajo dos lectores del mismo buzón?", "Dos consumidores en la misma cola"),
    ("¿Qué significa que algo esté esperando respuesta del lector?", "Pendiente de confirmar"),
])
async def test_real_embeddings_find_the_right_chunk_without_shared_words(question, expected):
    """Con Ollama y bge-m3 de verdad: `uv run pytest -m evaluation tests/test_docs.py`."""
    from app.llm import OllamaLlm
    llm = OllamaLlm(os.environ.get("ASSISTANT_OLLAMA_URL", "http://localhost:11434"), "qwen2.5:3b", "bge-m3", {})
    index = DocIndex(llm, "bge-m3", ROOT / "assistant" / ".index")
    await index.build(docs.load(ROOT))
    assert any(expected.lower() in chunk.title.lower() for chunk in await index.search(question, 3))


def test_the_history_of_a_spec_is_not_indexed_because_it_is_not_the_current_state():
    spec = "# Spec\n## 1. Contexto y objetivo\nLa sección está deshabilitada.\n### Detalle\nantes\n## 2. Requisitos\nRF-1\n## Fuera de alcance\nKafka\n"
    assert [chunk.title for chunk in docs.markdown(spec, "Especificación 02", docs.HISTORY_SECTIONS)] == [
        "Especificación 02 › 2. Requisitos"]
    assert len(docs.markdown(spec, "README")) == 4


def test_the_repository_index_has_no_spec_context_sections():
    titles = [chunk.title for chunk in docs.load(ROOT)]
    assert not [title for title in titles if "Contexto y objetivo" in title or "Fuera de alcance" in title]
    assert any(title.startswith("Especificación 02") for title in titles)
