"""Thin wrapper over tree-sitter.

Deliberately avoids the tree-sitter Query API, whose signature has changed
several times across releases. Everything here uses only node.type,
node.children, node.child_by_field_name and byte offsets, which have been
stable for years. Walking is marginally slower than querying and vastly
more durable.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Iterator

from tree_sitter import Language, Node, Parser

import tree_sitter_kotlin
import tree_sitter_rust
import tree_sitter_swift

_LANGS = {
    "kotlin": Language(tree_sitter_kotlin.language()),
    "rust": Language(tree_sitter_rust.language()),
    "swift": Language(tree_sitter_swift.language()),
}

EXT_TO_LANG = {".kt": "kotlin", ".kts": "kotlin", ".rs": "rust", ".swift": "swift"}


@dataclass
class ParseResult:
    lang: str
    root: Node
    source: bytes
    had_error: bool


def parse(source: bytes, lang: str) -> ParseResult:
    parser = Parser(_LANGS[lang])
    tree = parser.parse(source)
    return ParseResult(lang, tree.root_node, source, tree.root_node.has_error)


def walk(node: Node) -> Iterator[Node]:
    """Depth-first pre-order traversal of every node in the tree."""
    stack = [node]
    while stack:
        n = stack.pop()
        yield n
        stack.extend(reversed(n.children))


def text(node: Node, source: bytes) -> str:
    return source[node.start_byte : node.end_byte].decode("utf8", errors="replace")


def field(node: Node, name: str) -> Node | None:
    return node.child_by_field_name(name)


def child_of_type(node: Node, type_name: str) -> Node | None:
    for c in node.children:
        if c.type == type_name:
            return c
    return None


def children_of_type(node: Node, type_name: str) -> list[Node]:
    return [c for c in node.children if c.type == type_name]
