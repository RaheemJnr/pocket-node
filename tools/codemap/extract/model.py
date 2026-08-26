"""Language-neutral extraction model.

Both extractors produce these types and nothing else, so the graph layer
never imports a language-specific module. Adding Swift for iOS means
adding one producer of RawFile, not touching anything downstream.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from functools import cached_property


def content_hash(source: str) -> str:
    """Hash a declaration's text, ignoring position and trailing whitespace.

    Moving a function within a file must not invalidate its cached summary,
    so line numbers are deliberately not part of the hash.
    """
    normalized = "\n".join(line.rstrip() for line in source.strip().splitlines())
    return hashlib.sha256(normalized.encode("utf8")).hexdigest()[:12]


@dataclass
class RawDecl:
    kind: str                       # "type" | "function" | "property"
    name: str
    start_line: int
    end_line: int
    source: str
    qualifier: str = ""             # enclosing type name, "" if top-level
    annotations: list[str] = field(default_factory=list)
    modifiers: list[str] = field(default_factory=list)
    param_types: list[str] = field(default_factory=list)
    return_type: str = ""
    supertypes: list[str] = field(default_factory=list)
    doc: str = ""                   # authored doc comment, "" if none
    calls: list[str] = field(default_factory=list)      # raw, unresolved
    local_types: dict[str, str] = field(default_factory=dict)  # name -> type

    @cached_property
    def content_hash(self) -> str:
        return content_hash(self.source)


@dataclass
class RawFile:
    path: str                       # repo-relative
    lang: str
    module: str                     # kotlin package / rust mod path
    imports: list[str] = field(default_factory=list)
    aliases: dict[str, str] = field(default_factory=dict)   # alias -> fqn
    decls: list[RawDecl] = field(default_factory=list)
    parse_error: bool = False
