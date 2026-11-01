"""El contrato `api/api.yml` y la implementación dicen lo mismo (sin librería de YAML: lo justo para no desviarse)."""

import re
from pathlib import Path

from app.main import create_app
from app.service import MAX_QUESTION_CHARS, State

CONTRACT = (Path(__file__).resolve().parents[1] / "api" / "api.yml").read_text(encoding="utf-8")


def schema(name: str) -> str:
    return re.search(rf"^    {name}:\n(.*?)(?=^    \w+:$|\Z)", CONTRACT, re.MULTILINE | re.DOTALL).group(1)


def enum(block: str, field: str) -> list[str]:
    values = re.search(rf"{field}:\n(?:.*\n)*?\s+enum:\n((?:\s+- \w+\n)+)", block).group(1)
    return re.findall(r"- (\w+)", values)


def test_the_contract_has_the_operations_the_service_serves():
    served = {(method, route.path) for route in create_app().routes for method in getattr(route, "methods", ())
              if route.path.startswith("/api/")}
    assert served == {("GET", "/api/assistant/status"), ("POST", "/api/assistant/chat")}
    assert set(re.findall(r"^  (/api/\S+):", CONTRACT, re.MULTILINE)) == {path for _, path in served}


def test_the_states_roles_limits_and_error_codes_match():
    assert enum(schema("AssistantStatus"), "state") == [state.value for state in State]
    assert enum(schema("AssistantTurn"), "role") == ["USER", "ASSISTANT"]
    assert f"maxLength: {MAX_QUESTION_CHARS}" in schema("AssistantChatRequest")
    assert "maxItems: 8" in schema("AssistantChatRequest") and "maxLength: 2000" in schema("AssistantTurn")
    assert enum(schema("ApiError"), "code") == ["VALIDATION", "CONFLICT", "ASSISTANT_UNAVAILABLE"]
    for code in ("'400'", "'409'", "'503'"):
        assert code in CONTRACT
