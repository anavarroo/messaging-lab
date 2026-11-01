"""
Búsqueda en la documentación por significado: el README, las especificaciones y la guía, troceados por secciones e
indexados con embeddings locales. El índice se guarda en disco con un hash del contenido, así que solo se recalcula si
la documentación o el modelo cambian.
"""

import asyncio
import hashlib
import json
import logging
import re
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path

import numpy as np

from app.llm import Llm
from app.words import normalize

log = logging.getLogger(__name__)

# un trozo más largo se parte por párrafos: así cada uno trata de una sola cosa
MAX_CHUNK_CHARS = 1200

BATCH = 16

GUIDE = "frontend/src/app/features/rabbitmq/guide/guide-content.ts"

# secciones de las especificaciones que cuentan la historia (cómo estaba el proyecto antes, qué se decidió) y no
# lo que hay ahora: un modelo pequeño las toma por el estado actual («la sección está deshabilitada»)
HISTORY_SECTIONS = ("contexto", "fuera de alcance", "decisiones", "puntos a decidir", "preguntas abiertas")


@dataclass(frozen=True)
class Chunk:
    source: str
    title: str
    text: str


def load(root: Path) -> list[Chunk]:
    """Los trozos de toda la documentación que haya bajo `root` (las rutas son las del repositorio)."""
    chunks: list[Chunk] = []
    readme = root / "README.md"
    if readme.is_file():
        chunks += markdown(readme.read_text(encoding="utf-8"), "README")
    for spec in sorted(root.glob("specs/*/spec.md")):
        chunks += markdown(spec.read_text(encoding="utf-8"), f"Especificación {spec.parent.name}", HISTORY_SECTIONS)
    guide = root / GUIDE
    if guide.is_file():
        chunks += guide_content(guide.read_text(encoding="utf-8"))
    return chunks


def markdown(text: str, source: str, skip: tuple[str, ...] = ()) -> list[Chunk]:
    """
    Un trozo por sección (encabezados `#` a `####`), con el camino de encabezados como título. Se saltan las
    secciones (y sus subsecciones) cuyo título, sin numeración, empieza por alguna palabra de `skip`.
    """
    chunks: list[Chunk] = []
    path: list[str] = []
    body: list[str] = []

    def flush() -> None:
        content = "\n".join(body).strip()
        skipped = any(normalize(re.sub(r"^[\d.\s]+", "", heading)).startswith(skip) for heading in path) if skip else False
        if content and not skipped:
            title = " › ".join([source, *path[1:]] if len(path) > 1 else [source, *path])
            chunks.extend(Chunk(source, title, part) for part in _split(content))
        body.clear()

    for line in text.splitlines():
        heading = re.match(r"^(#{1,4})\s+(.*)", line)
        if heading:
            flush()
            level = len(heading.group(1))
            path[level - 1:] = [heading.group(2).strip()]
        else:
            body.append(line)
    flush()
    return chunks


def guide_content(source: str) -> list[Chunk]:
    """Un trozo por cada elemento, idea, problema y término de la guía, con sus textos unidos."""
    source = re.sub(r"lab\('[^']*', '[^']*'\)", "", source)
    chunks = []
    blocks = re.findall(r"^  \{\n(.*?)^  \},?$|^  \{ ([^\n]*) \},?$", source, flags=re.MULTILINE | re.DOTALL)
    for block in (multiline or inline for multiline, inline in blocks):
        fields = dict(re.findall(r"(\w+):\s*'((?:[^'\\]|\\.)*)'", block))
        title = next((fields[key] for key in ("name", "title", "problem", "term", "pattern") if key in fields), None)
        texts = [_unescape(text) for text in re.findall(r"'((?:[^'\\]|\\.)*)'", block)
                 if text != fields.get("id")]
        if title and len(texts) > 1:
            chunks.append(Chunk("Guía", f"Guía › {_unescape(title)}", "\n".join(texts)))
    return chunks


def _split(content: str) -> list[str]:
    if len(content) <= MAX_CHUNK_CHARS:
        return [content]
    parts, current = [], ""
    # los párrafos, y las líneas de los que no caben solos (listas largas, tablas)
    pieces = [line for paragraph in re.split(r"\n\s*\n", content)
              for line in (paragraph.splitlines() if len(paragraph) > MAX_CHUNK_CHARS else [paragraph])]
    for paragraph in pieces:
        if current and len(current) + len(paragraph) > MAX_CHUNK_CHARS:
            parts.append(current.strip())
            current = ""
        while len(paragraph) > MAX_CHUNK_CHARS:
            cut = paragraph.rfind(" ", 0, MAX_CHUNK_CHARS)
            cut = cut if cut > 0 else MAX_CHUNK_CHARS
            parts.append((current + paragraph[:cut]).strip())
            current, paragraph = "", paragraph[cut:]
        current += paragraph + "\n\n"
    if current.strip():
        parts.append(current.strip())
    return parts


def _unescape(text: str) -> str:
    return text.replace("\\'", "'").replace('\\"', '"')


class IndexState(StrEnum):
    EMPTY = "EMPTY"
    BUILDING = "BUILDING"
    READY = "READY"
    FAILED = "FAILED"


class DocIndex:
    """El índice en memoria: una matriz de embeddings normalizados y sus trozos."""

    def __init__(self, llm: Llm, model: str, cache_dir: Path | None = None):
        self._llm = llm
        self._model = model
        self._cache_dir = cache_dir
        self._chunks: list[Chunk] = []
        self._vectors = np.zeros((0, 0), dtype=np.float32)
        self.state = IndexState.EMPTY

    @property
    def ready(self) -> bool:
        return self.state == IndexState.READY

    async def build(self, chunks: list[Chunk]) -> None:
        """Calcula los embeddings de los trozos, o los lee del disco si ya se calcularon con este mismo contenido."""
        self.state = IndexState.BUILDING
        try:
            digest = hashlib.sha256(json.dumps([self._model, *map(asdict, chunks)]).encode()).hexdigest()[:16]
            cached = self._cache_dir / f"index-{digest}.npy" if self._cache_dir else None
            if cached and cached.is_file():
                vectors = np.load(cached)
            else:
                rows: list[list[float]] = []
                for start in range(0, len(chunks), BATCH):
                    rows += await self._llm.embed([_embedded(chunk) for chunk in chunks[start:start + BATCH]])
                vectors = _normalized(np.array(rows, dtype=np.float32))
                if cached:
                    cached.parent.mkdir(parents=True, exist_ok=True)
                    for old in cached.parent.glob("index-*.npy"):
                        old.unlink()
                    np.save(cached, vectors)
            self._chunks, self._vectors = chunks, vectors
            self.state = IndexState.READY
            log.info("Documentación indexada: %d trozos", len(chunks))
        except Exception:
            self.state = IndexState.FAILED
            raise

    async def search(self, query: str, limit: int = 3) -> list[Chunk]:
        """Los trozos más parecidos en significado a la consulta; ninguno si el índice no está listo."""
        if not self.ready or not self._chunks:
            return []
        vector = _normalized(np.array(await self._llm.embed([query]), dtype=np.float32))[0]
        scores = self._vectors @ vector
        return [self._chunks[i] for i in np.argsort(-scores)[:limit]]

    async def keep_built(self, chunks: list[Chunk], ready_to_embed, retry_seconds: float = 15.0) -> None:
        """Construye el índice en segundo plano en cuanto el modelo de embeddings esté, reintentando si falla."""
        while not self.ready:
            try:
                if await ready_to_embed():
                    await self.build(chunks)
                    return
            except Exception:
                log.warning("No se ha podido indexar la documentación; se reintenta", exc_info=True)
            await asyncio.sleep(retry_seconds)


def _embedded(chunk: Chunk) -> str:
    return f"{chunk.title}\n{chunk.text}"


def _normalized(matrix: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    return matrix / np.where(norms == 0, 1, norms)
