"""Configuración del asistente, desde variables de entorno ASSISTANT_* (ver infra/.env.example)."""

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    ollama_url: str = "http://localhost:11434"
    model: str = "qwen2.5:3b"
    embedding_model: str = "bge-m3"
    backend_url: str = "http://localhost:8080"
    # tamaño del contexto del modelo: instrucciones, herramientas, contexto, conversación y respuesta
    context_tokens: int = 4096
    max_response_tokens: int = 700
    temperature: float = 0.2
    # documentación que se indexa (README, specs y guía, con las mismas rutas que en el repositorio)
    docs_dir: Path = Path(__file__).resolve().parents[2]
    # dónde se guarda el índice de la documentación, para no recalcularlo si no cambia
    index_dir: Path = Path(__file__).resolve().parents[1] / ".index"

    @staticmethod
    def from_env() -> "Settings":
        env = os.environ
        default = Settings()
        return Settings(
            ollama_url=env.get("ASSISTANT_OLLAMA_URL", default.ollama_url),
            model=env.get("ASSISTANT_MODEL", default.model),
            embedding_model=env.get("ASSISTANT_EMBEDDING_MODEL", default.embedding_model),
            backend_url=env.get("ASSISTANT_BACKEND_URL", default.backend_url),
            context_tokens=int(env.get("ASSISTANT_CONTEXT_TOKENS", default.context_tokens)),
            max_response_tokens=int(env.get("ASSISTANT_MAX_RESPONSE_TOKENS", default.max_response_tokens)),
            temperature=float(env.get("ASSISTANT_TEMPERATURE", default.temperature)),
            docs_dir=Path(env.get("ASSISTANT_DOCS_DIR", default.docs_dir)),
            index_dir=Path(env.get("ASSISTANT_INDEX_DIR", default.index_dir)),
        )
