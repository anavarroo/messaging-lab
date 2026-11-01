"""Texto en minúsculas y sin tildes, para comparar palabras sin que importe cómo se escriban."""

import unicodedata


def normalize(text: str | None) -> str:
    decomposed = unicodedata.normalize("NFD", (text or "").lower())
    return "".join(char for char in decomposed if not unicodedata.combining(char))


def number(value: float) -> str:
    """Una cifra como la escribe el backend: sin decimales si es entera y, si no, con uno y coma decimal."""
    return str(int(value)) if value == int(value) else f"{value:.1f}".replace(".", ",")
