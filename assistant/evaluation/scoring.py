"""Puntuación de la evaluación: si una respuesta contiene lo que debe y no lo que no debe, y el informe."""

import json
import statistics
from dataclasses import dataclass, field
from pathlib import Path

from app.words import normalize

BANK = Path(__file__).with_name("questions.json")


@dataclass
class Result:
    id: str
    kind: str
    question: str
    answer: str
    first_token_s: float | None
    total_s: float
    missing: list[str] = field(default_factory=list)
    forbidden: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not self.missing and not self.forbidden


def questions() -> list[dict]:
    return json.loads(BANK.read_text(encoding="utf-8"))["questions"]


def check(answer: str, must: list[list[str]], must_not: list[str]) -> tuple[list[str], list[str]]:
    """Los grupos que faltan (los muestra como «a | b») y las frases prohibidas que aparecen."""
    text = normalize(answer)
    missing = [" | ".join(group) for group in must if not any(normalize(option) in text for option in group)]
    forbidden = [phrase for phrase in must_not if normalize(phrase) in text]
    return missing, forbidden


def report(results: list[Result], model: str) -> str:
    """El informe en Markdown: acierto total y por tipo, latencias y el detalle de los fallos."""
    passed = sum(result.passed for result in results)
    lines = [f"# Evaluación del asistente ({model})", "",
             f"**Acierto: {passed}/{len(results)} ({100 * passed / len(results):.0f} %)**", "",
             "| Tipo | Acierto |", "|---|---|"]
    for kind in sorted({result.kind for result in results}):
        group = [result for result in results if result.kind == kind]
        lines.append(f"| {kind} | {sum(r.passed for r in group)}/{len(group)} |")
    firsts = [result.first_token_s for result in results if result.first_token_s is not None]
    totals = [result.total_s for result in results]
    lines += ["", f"Primera palabra: mediana {statistics.median(firsts):.1f} s, máximo {max(firsts):.1f} s. "
                  f"Respuesta completa: mediana {statistics.median(totals):.1f} s, máximo {max(totals):.1f} s.", ""]
    failed = [result for result in results if not result.passed]
    if failed:
        lines += ["## Fallos", ""]
        for result in failed:
            why = "; ".join([f"falta {m}" for m in result.missing] + [f"no debería decir «{f}»" for f in result.forbidden])
            lines += [f"- **{result.id}** — {result.question} → {why}", f"  > {' '.join(result.answer.split())}"]
    return "\n".join(lines) + "\n"


def accuracy(results: list[Result]) -> float:
    return sum(result.passed for result in results) / len(results)
