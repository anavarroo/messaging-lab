"""
Evaluación automática contra el stack levantado (RF-5). No se ejecuta con las pruebas normales:

    uv run pytest -m evaluation tests/test_evaluation.py -s

Variables: EVAL_URL (por defecto http://localhost:4200, el frontend, que reparte /api entre backend y asistente),
EVAL_RABBITMQ_CONTAINER (por defecto messaging-lab-rabbitmq-1, para apagar el trazado), EVAL_MODEL (solo para el
informe) y EVAL_THRESHOLD (acierto mínimo, por defecto 0.8). Usa un stack dedicado: borra todo lo que empiece por
«eval-», para y levanta RabbitMQ y espera que no haya nada más en el laboratorio.
"""

import json
import os
import time
from pathlib import Path

import httpx
import pytest

from evaluation import scoring
from evaluation.lab import BEFORE_EACH_QUESTION, SCENARIOS, Lab

REPORT = Path(__file__).resolve().parents[1] / "evaluation" / "last-report.md"


def ask(http: httpx.Client, question: str) -> tuple[str, float | None, float]:
    """La respuesta completa, el tiempo hasta la primera palabra y el total."""
    start = time.monotonic()
    first, text, event = None, "", None
    with http.stream("POST", "/api/assistant/chat", json={"question": question}) as response:
        if response.status_code != 200:
            response.read()
            return f"[HTTP {response.status_code}] {response.text}", None, time.monotonic() - start
        for line in response.iter_lines():
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data = json.loads(line[5:])
                if event == "token":
                    first = first or time.monotonic() - start
                    text += data["text"]
                elif event == "error":
                    text += f" [ERROR {data['message']}]"
    return text, first, time.monotonic() - start


@pytest.mark.evaluation
def test_the_assistant_answers_the_bank_well_enough():
    base = os.environ.get("EVAL_URL", "http://localhost:4200")
    lab = Lab(base, os.environ.get("EVAL_RABBITMQ_CONTAINER", "messaging-lab-rabbitmq-1"))
    http = httpx.Client(base_url=base, timeout=180.0)
    lab.wait(lambda: http.get("/api/assistant/status").json()["state"] == "READY", 600, "asistente listo")

    bank = scoring.questions()
    results: list[scoring.Result] = []
    try:
        for name, prepare in SCENARIOS.items():
            lab.reset()
            prepare(lab)
            for item in (question for question in bank if question["scenario"] == name):
                if name in BEFORE_EACH_QUESTION:
                    BEFORE_EACH_QUESTION[name](lab)
                answer, first, total = ask(http, item["question"])
                missing, forbidden = scoring.check(answer, item["must"], item.get("must_not", []))
                results.append(scoring.Result(item["id"], item["kind"], item["question"], answer, first, total,
                                              missing, forbidden))
                print(f"{'OK ' if results[-1].passed else 'MAL'} {item['id']}: {' '.join(answer.split())[:140]}")
    finally:
        lab.reset()

    text = scoring.report(results, os.environ.get("EVAL_MODEL", "modelo por defecto"))
    REPORT.write_text(text, encoding="utf-8")
    print("\n" + text)
    assert scoring.accuracy(results) >= float(os.environ.get("EVAL_THRESHOLD", "0.8")), f"Informe en {REPORT}"
