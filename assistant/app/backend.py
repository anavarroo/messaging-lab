"""
Cliente del backend del laboratorio. Solo hace consultas GET: el asistente no tiene forma de cambiar nada. Las
respuestas no incluyen cuerpos de mensajes.
"""

from typing import Any

import httpx

from app.errors import UnavailableError

UNAVAILABLE = ("El asistente no puede consultar el laboratorio porque el backend no responde. Comprueba que está en "
               "marcha.")


class BackendClient:

    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None):
        # 15 s: justo después de pararse RabbitMQ, el backend tarda unos segundos en responder
        self._http = httpx.AsyncClient(base_url=base_url, timeout=httpx.Timeout(15.0, connect=2.0),
                                       transport=transport)

    async def snapshot(self) -> dict[str, Any]:
        """La foto del laboratorio: RabbitMQ, trazado, colas, exchanges, generador y carga."""
        return await self._get("/api/lab/snapshot")

    async def diagnostics(self) -> dict[str, Any]:
        """Las conclusiones de las reglas del backend, de la más grave a la menos."""
        return await self._get("/api/lab/diagnostics")

    async def activity(self) -> dict[str, Any]:
        """Qué se mueve ahora mismo, ya redactado."""
        return await self._get("/api/lab/activity")

    async def available(self) -> bool:
        try:
            await self._get("/api/status")
            return True
        except UnavailableError:
            return False

    async def close(self) -> None:
        await self._http.aclose()

    async def _get(self, path: str) -> dict[str, Any]:
        try:
            response = await self._http.get(path)
            response.raise_for_status()
            return response.json()
        except (httpx.HTTPError, ValueError) as error:
            raise UnavailableError(UNAVAILABLE) from error
