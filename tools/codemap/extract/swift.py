"""Swift declaration extractor.

Mirrors extract/kotlin.py. Swift has no package statement, so the module
is derived from the directory, the way SwiftPM targets are laid out.

`class_declaration` covers struct, class, enum and extension; for an
extension the name field carries the extended type, so members land on
the type they extend, which is what you want in the graph.
"""
from __future__ import annotations

from pathlib import Path

from tree_sitter import Node

from .model import RawDecl, RawFile
from .node_types import SW
from .ts_api import child_of_type, children_of_type, field, parse, text, walk


def _line(node: Node) -> int:
    return node.start_point[0] + 1


def module_path(rel_path: str) -> str:
    parts = list(Path(rel_path).parts[:-1])
    for drop in ("ios", "Sources", "src"):
        if parts and parts[0] == drop:
            parts = parts[1:]
    return ".".join(parts)


def _doc_above(src_text: str, decl_line: int) -> str:
    lines = src_text.splitlines()
    i = decl_line - 2
    while i >= 0 and (not lines[i].strip() or lines[i].strip().startswith("@")):
        i -= 1
    collected: list[str] = []
    while i >= 0 and lines[i].strip().startswith("//"):
        collected.append(lines[i].strip().lstrip("/").strip())
        i -= 1
    collected.reverse()
    return " ".join(c for c in collected if c).strip()


def _modifiers(node: Node, src: bytes) -> tuple[list[str], list[str]]:
    mods: list[str] = []
    attrs: list[str] = []
    m = child_of_type(node, SW.MODIFIERS)
    if m is None:
        return mods, attrs
    for c in m.children:
        t = text(c, src).strip()
        (attrs if t.startswith("@") else mods).append(t.lstrip("@"))
    return mods, attrs


def _enclosing_type(node: Node, src: bytes) -> str:
    p = node.parent
    while p is not None:
        if p.type in SW.TYPE_DECLS:
            n = field(p, "name")
            return text(n, src) if n else ""
        p = p.parent
    return ""


def _collect_calls(fn: Node, src: bytes) -> list[str]:
    out: list[str] = []
    for n in walk(fn):
        if n.type != SW.CALL or not n.children:
            continue
        head = n.children[0]
        if head.type in (SW.NAVIGATION, SW.IDENT):
            out.append(text(head, src).strip())
    return out


def _member_types(type_node: Node, src: bytes) -> dict[str, str]:
    out: dict[str, str] = {}
    body = child_of_type(type_node, SW.CLASS_BODY)
    if body is None:
        return out
    for prop in children_of_type(body, SW.PROPERTY):
        ident = next((n for n in walk(prop) if n.type == SW.IDENT), None)
        ann = next((n for n in walk(prop) if n.type == SW.USER_TYPE), None)
        if ident is not None and ann is not None:
            out[text(ident, src)] = text(ann, src).split("<")[0].strip()
    return out


def extract_swift(abs_path: Path, rel_path: str) -> RawFile:
    src = abs_path.read_bytes()
    src_text = src.decode("utf8", errors="replace")
    res = parse(src, "swift")

    rf = RawFile(path=rel_path, lang="swift", module=module_path(rel_path),
                 parse_error=res.had_error)

    for node in walk(res.root):
        if node.type == SW.IMPORT:
            rf.imports.append(text(node, src).replace("import", "").strip())

    members_by_owner: dict[str, dict[str, str]] = {}

    for node in walk(res.root):
        if node.type not in SW.TYPE_DECLS:
            continue
        name_node = field(node, "name")
        if name_node is None:
            continue
        name = text(name_node, src)
        mods, attrs = _modifiers(node, src)
        members_by_owner.setdefault(name, {}).update(_member_types(node, src))
        rf.decls.append(RawDecl(
            kind="type", name=name, start_line=_line(node),
            end_line=node.end_point[0] + 1, source=text(node, src),
            qualifier=_enclosing_type(node, src), annotations=attrs,
            modifiers=mods, doc=_doc_above(src_text, _line(node)),
        ))

    for node in walk(res.root):
        if node.type not in (SW.FUNCTION, SW.PROTOCOL_FUNCTION):
            continue
        name_node = field(node, "name")
        if name_node is None:
            continue
        owner = _enclosing_type(node, src)
        mods, attrs = _modifiers(node, src)
        param_types = [
            text(u, src).split("<")[0].strip()
            for p in walk(node) if p.type == SW.PARAMS
            for u in walk(p) if u.type == SW.USER_TYPE
        ]
        scope = dict(members_by_owner.get(owner, {}))
        rf.decls.append(RawDecl(
            kind="function", name=text(name_node, src), start_line=_line(node),
            end_line=node.end_point[0] + 1, source=text(node, src),
            qualifier=owner, annotations=attrs, modifiers=mods,
            param_types=param_types, doc=_doc_above(src_text, _line(node)),
            calls=_collect_calls(node, src), local_types=scope,
        ))

    return rf
