# Imagen del asistente de IA: Python 3.12 con uv, dependencias fijadas en uv.lock.
FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never

# Primero las dependencias, para reaprovechar la capa mientras no cambien
COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --locked --no-dev

COPY app ./app

# Usuario sin privilegios. El índice de la documentación se guarda en un volumen (ver infra/docker-compose.yml)
RUN useradd --system --uid 10001 assistant && mkdir -p /data/index && chown assistant /data/index
USER assistant

ENV PATH=/app/.venv/bin:$PATH \
    ASSISTANT_DOCS_DIR=/docs \
    ASSISTANT_INDEX_DIR=/data/index
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
