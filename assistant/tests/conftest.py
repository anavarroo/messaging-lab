import pytest

from app.docs import DocIndex, IndexState
from app.service import AssistantService
from tests.fakes import FakeBackend, FakeLlm


class Clock:

    def __init__(self):
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def clock() -> Clock:
    return Clock()


@pytest.fixture
def backend() -> FakeBackend:
    return FakeBackend()


@pytest.fixture
def llm() -> FakeLlm:
    return FakeLlm()


@pytest.fixture
def index(llm) -> DocIndex:
    index = DocIndex(llm, "bge-m3")
    index.state = IndexState.READY
    return index


@pytest.fixture
def service(backend, llm, index, clock) -> AssistantService:
    return AssistantService(backend.client(), llm, index, "qwen2.5:3b", "bge-m3", clock)
