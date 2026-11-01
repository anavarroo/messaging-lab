"""
Monta los escenarios de la evaluación con la API del backend (a diferencia del asistente, la evaluación sí cambia el
laboratorio). Todo lo que crea empieza por «eval-» y se borra al empezar cada escenario. Pensado para un stack
dedicado a la evaluación: las preguntas cuentan con que no haya nada más en el laboratorio.
"""

import subprocess
import time
from collections.abc import Callable

import httpx

PREFIX = "eval-"

FIXED = lambda ms: {"mode": "FIXED", "fixedMs": ms}  # noqa: E731


class Lab:

    def __init__(self, base_url: str, rabbitmq_container: str):
        self.http = httpx.Client(base_url=base_url, timeout=60.0)
        self.rabbitmq_container = rabbitmq_container

    # ---- utilidades ----

    def _ok(self, response: httpx.Response) -> dict:
        if response.status_code >= 400:
            raise RuntimeError(f"{response.request.method} {response.request.url.path}: {response.status_code} {response.text}")
        return response.json() if response.content else {}

    def wait(self, condition: Callable[[], bool], seconds: float, what: str) -> None:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                if condition():
                    return
            except (httpx.HTTPError, KeyError, IndexError):
                pass
            time.sleep(1)
        raise TimeoutError(f"El escenario no se ha preparado a tiempo: {what}")

    def snapshot(self) -> dict:
        return self._ok(self.http.get("/api/lab/snapshot"))

    def queue(self, name: str) -> dict:
        return next(queue for queue in self.snapshot()["queues"] if queue["name"] == name)

    def rabbitmq_up(self) -> bool:
        return self.http.get("/api/rabbitmq/status").status_code == 200

    # ---- piezas ----

    def create_queue(self, name: str, dead_letter: str | None = None) -> None:
        self._ok(self.http.post("/api/rabbitmq/queues", json={"name": name, "deadLetterQueue": dead_letter}))

    def create_exchange(self, name: str, kind: str = "DIRECT") -> None:
        self._ok(self.http.post("/api/rabbitmq/exchanges", json={"name": name, "type": kind}))

    def emitter(self, name: str, destination: dict, every_ms: int, max_messages: int | None = None) -> str:
        created = self._ok(self.http.post("/api/rabbitmq/generator/emitters", json={
            "name": name, "destination": destination, "interval": FIXED(every_ms),
            "template": '{"n": {{numero(1,9)}}}', "maxMessages": max_messages}))
        self.http.post(f"/api/rabbitmq/generator/emitters/{created['id']}/actions", json={"action": "START"})
        return created["id"]

    def simulated_consumer(self, name: str, queue: str, processing_ms: int, failure_percent: int = 0,
                           on_failure: str = "REQUEUE") -> str:
        created = self._ok(self.http.post("/api/rabbitmq/generator/consumers", json={
            "name": name, "queue": queue, "processing": FIXED(processing_ms), "failurePercent": failure_percent,
            "onFailure": on_failure}))
        self.http.post(f"/api/rabbitmq/generator/consumers/{created['id']}/actions", json={"action": "START"})
        return created["id"]

    def trace(self, on: bool) -> None:
        subprocess.run(["docker", "exec", self.rabbitmq_container, "rabbitmqctl", "trace_on" if on else "trace_off"],
                       check=True, capture_output=True)

    def control(self, action: str) -> None:
        self._ok(self.http.post("/api/control/services/rabbitmq/actions", json={"action": action}))

    # ---- limpieza ----

    def reset(self) -> None:
        """Deja el laboratorio sin nada de la evaluación, con RabbitMQ levantado y el trazado activo."""
        if not self.rabbitmq_up():
            self.control("START")
            self.wait(self.rabbitmq_up, 90, "RabbitMQ vuelve")
        self.trace(True)
        self.http.post("/api/rabbitmq/generator/stop-all")
        for kind in ("emitters", "consumers"):
            for item in self._ok(self.http.get(f"/api/rabbitmq/generator/{kind}")):
                if item["name"].startswith(PREFIX):
                    self.http.delete(f"/api/rabbitmq/generator/{kind}/{item['id']}")
        lab = self.snapshot()
        for queue in lab["queues"]:
            if queue["name"].startswith(PREFIX):
                self.http.delete("/api/rabbitmq/queues", params={"name": queue["name"]})
        for exchange in lab["exchanges"]:
            if exchange["name"].startswith(PREFIX):
                self.http.delete("/api/rabbitmq/exchanges", params={"name": exchange["name"]})
        self.wait(lambda: self.snapshot()["trace"]["state"] == "ACTIVE", 30, "trazado activo")


# ---- escenarios: cada uno deja el laboratorio en un estado concreto ----

def clean(lab: Lab) -> None:
    """Nada en marcha."""


def growing_queue(lab: Lab) -> None:
    """eval-cocina recibe 10 mensajes por segundo de eval-horno y eval-cocinero solo procesa unos 4."""
    lab.create_queue("eval-cocina")
    lab.simulated_consumer("eval-cocinero", "eval-cocina", 250)
    lab.emitter("eval-horno", {"type": "QUEUE", "queue": "eval-cocina"}, 100)
    lab.wait(lambda: lab.queue("eval-cocina")["ready"] > 20 and (lab.queue("eval-cocina")["inPerSecond"] or 0) > 5,
             60, "eval-cocina crece")


def queue_without_consumer(lab: Lab) -> None:
    """eval-barra tiene 15 mensajes y nadie lee de ella."""
    lab.create_queue("eval-barra")
    lab.emitter("eval-camarero", {"type": "QUEUE", "queue": "eval-barra"}, 100, max_messages=15)
    lab.wait(lambda: lab.queue("eval-barra")["ready"] == 15, 30, "eval-barra con 15 mensajes")


def dead_letter(lab: Lab) -> None:
    """eval-pagos rechaza sin reencolar todo lo que recibe: sus mensajes acaban en eval-pagos-fallidos."""
    lab.create_queue("eval-pagos-fallidos")
    lab.create_queue("eval-pagos", dead_letter="eval-pagos-fallidos")
    consumer = lab.simulated_consumer("eval-cobrador", "eval-pagos", 20, failure_percent=100, on_failure="REJECT")
    lab.emitter("eval-caja", {"type": "QUEUE", "queue": "eval-pagos"}, 100, max_messages=8)
    lab.wait(lambda: lab.queue("eval-pagos-fallidos")["ready"] == 8, 30, "8 mensajes en eval-pagos-fallidos")
    lab.http.post(f"/api/rabbitmq/generator/consumers/{consumer}/actions", json={"action": "STOP"})


def unrouted(lab: Lab) -> None:
    """eval-sirena publica en el exchange eval-avisos, que no tiene bindings."""
    lab.create_exchange("eval-avisos")
    lab.emitter("eval-sirena", {"type": "EXCHANGE", "exchange": "eval-avisos", "routingKey": "alarma"}, 200,
                max_messages=10)
    lab.wait(lambda: next(e for e in lab.snapshot()["emitters"] if e["name"] == "eval-sirena")["unrouted"] >= 10, 30,
             "10 mensajes sin enrutar")


def trace_off(lab: Lab) -> None:
    """El trazado de RabbitMQ está desactivado."""
    lab.trace(False)
    lab.wait(lambda: lab.snapshot()["trace"]["state"] == "DISABLED", 20, "trazado desactivado")


def keep_trace_off(lab: Lab) -> None:
    """El Compose vuelve a activar el trazado cada 15 s: se apaga otra vez antes de cada pregunta."""
    trace_off(lab)


def failed_emitter(lab: Lab) -> None:
    """eval-sensor publicaba en eval-temporal, que se ha borrado: el emisor se ha parado por un error."""
    lab.create_queue("eval-temporal")
    lab.emitter("eval-sensor", {"type": "QUEUE", "queue": "eval-temporal"}, 500)
    time.sleep(2)
    lab.http.delete("/api/rabbitmq/queues", params={"name": "eval-temporal"})
    lab.wait(lambda: next(e for e in lab.snapshot()["emitters"] if e["name"] == "eval-sensor")["status"] == "FAILED", 30,
             "eval-sensor fallado")


def rabbitmq_down(lab: Lab) -> None:
    """RabbitMQ está parado (con el control de «Estado»), tras dejar una cola creada."""
    lab.create_queue("eval-cocina")
    lab.control("STOP")
    lab.wait(lambda: not lab.snapshot()["broker"]["available"], 30, "RabbitMQ caído")


# lo que hay que repetir antes de cada pregunta para que el escenario siga en pie
BEFORE_EACH_QUESTION: dict[str, Callable[[Lab], None]] = {"trace_off": keep_trace_off}

SCENARIOS: dict[str, Callable[[Lab], None]] = {scenario.__name__: scenario for scenario in (
    clean, growing_queue, queue_without_consumer, dead_letter, unrouted, trace_off, failed_emitter, rabbitmq_down)}
