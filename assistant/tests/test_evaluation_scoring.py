from evaluation import scoring
from evaluation.lab import SCENARIOS
from evaluation.scoring import Result


def test_a_group_passes_with_any_of_its_options_ignoring_accents_and_case():
    assert scoring.check("La cola «eval-cocina» CRECE: entran más de los que salen.",
                         [["eval-cocina"], ["entran", "entra"], ["crecé"]], []) == ([], [])


def test_reports_the_missing_groups_and_the_forbidden_phrases():
    assert scoring.check("He borrado la cola.", [["colas", "pestaña"]], ["he borrado", "eliminada"]) == (
        ["colas | pestaña"], ["he borrado"])


def test_the_bank_is_complete_and_uses_known_scenarios():
    questions = scoring.questions()
    assert len(questions) >= 30
    assert len({question["id"] for question in questions}) == len(questions)
    assert {question["scenario"] for question in questions} == set(SCENARIOS)
    assert {question["kind"] for question in questions} >= {"concepto", "accion", "ajena", "estado", "datos", "caido"}
    assert all(question["must"] and all(question["must"]) for question in questions)


def test_the_report_has_the_accuracy_the_latency_and_the_failures():
    results = [Result("a", "concepto", "¿Qué es?", "Bien.", 1.0, 2.0),
               Result("b", "accion", "Borra", "He borrado la cola.", 3.0, 4.0, forbidden=["he borrado"])]
    text = scoring.report(results, "qwen2.5:3b")
    assert "**Acierto: 1/2 (50 %)**" in text
    assert "| accion | 0/1 |" in text and "| concepto | 1/1 |" in text
    assert "Primera palabra: mediana 2.0 s, máximo 3.0 s" in text
    assert "**b** — Borra → no debería decir «he borrado»" in text
    assert scoring.accuracy(results) == 0.5
