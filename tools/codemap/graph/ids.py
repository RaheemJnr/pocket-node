"""Stable node identity.

IDs must survive edits elsewhere in a file, because the summary cache is
keyed on them. Arity is included so Kotlin overloads do not collapse into
one node -- the tradeoff is that adding a parameter expires that
function's cached summary, which is correct: a changed signature usually
means changed meaning.
"""
from __future__ import annotations

PREFIX = {"kotlin": "kt", "rust": "rs", "swift": "sw"}
SEP = {"kotlin": ".", "rust": "::", "swift": "."}


def module_id(lang: str, module: str) -> str:
    return f"{PREFIX[lang]}-mod:{module}"


def file_id(rel_path: str) -> str:
    return f"file:{rel_path}"


def type_id(lang: str, module: str, name: str) -> str:
    sep = SEP[lang]
    base = f"{module}{sep}{name}" if module else name
    return f"{PREFIX[lang]}:{base}"


def function_id(lang: str, module: str, qualifier: str, name: str, arity: int) -> str:
    sep = SEP[lang]
    owner = f"{module}{sep}{qualifier}" if qualifier else module
    return f"{PREFIX[lang]}:{owner}#{name}/{arity}"
