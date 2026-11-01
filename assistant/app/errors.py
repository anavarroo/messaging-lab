"""Errores con un mensaje en español dirigido al usuario."""


class AssistantError(Exception):
    """Error que se le puede contar al usuario tal cual."""

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class ValidationError(AssistantError):
    pass


class BusyError(AssistantError):
    pass


class UnavailableError(AssistantError):
    pass


class ToolsNotSupportedError(UnavailableError):
    """El modelo no sabe usar herramientas: se responde con el contexto preparado."""
