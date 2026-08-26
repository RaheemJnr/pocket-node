"""Rust declaration extractor.

Mirrors extract/kotlin.py: records what the AST says, resolves nothing.

`impl` blocks are not nodes in their own right -- they supply the
qualifier for the functions inside them, so `Store::open` reads the same
way `MiniRepository.cellCount` does on the Kotlin side.
"""
from __future__ import annotations

from pathlib import Path

from tree_sitter import Node

from .model import RawDecl, RawFile
from .node_types import RS
from .ts_api import field, parse, text, walk


def _line(node: Node) -> int:
    return node.start_point[0] + 1


def module_path(rel_path: str) -> str:
    """Derive a `::` module path from a file path.

    `mod.rs` and `lib.rs` collapse into their parent directory, matching
    how Rust itself names those modules.
    """
    p = Path(rel_path)
    parts = list(p.parts)
    if "src" in parts:
        parts = parts[parts.index("src") + 1 :]
    stem = Path(parts[-1]).stem if parts else ""
    segs = parts[:-1]
    if stem not in ("mod", "lib", "main"):
        segs = segs + [stem]
    return "::".join(s for s in segs if s)


def _doc_above(src_text: str, decl_line: int) -> str:
    """Contiguous `///` run above a declaration, skipping `#[...]` lines."""
    lines = src_text.splitlines()
    i = decl_line - 2
    while i >= 0 and (not lines[i].strip() or lines[i].strip().startswith("#[")):
        i -= 1
    collected: list[str] = []
    while i >= 0 and lines[i].strip().startswith("///"):
        collected.append(lines[i].strip()[3:].strip())
        i -= 1
    collected.reverse()
    return " ".join(c for c in collected if c).strip()


def _attributes_above(node: Node, src: bytes) -> list[str]:
    """Attribute names attached to a declaration, e.g. `no_mangle`."""
    out: list[str] = []
    sib = node.prev_sibling
    while sib is not None and sib.type in (RS.ATTRIBUTE, RS.LINE_COMMENT):
        if sib.type == RS.ATTRIBUTE:
            inner = next((c for c in sib.children if c.type == RS.ATTRIBUTE_INNER), None)
            out.append(text(inner, src).strip() if inner else text(sib, src).strip("#[]"))
        sib = sib.prev_sibling
    return out


def _impl_qualifier(node: Node, src: bytes) -> tuple[str, list[str]]:
    """Walk up to an enclosing impl block for the owning type and trait."""
    p = node.parent
    while p is not None:
        if p.type == RS.IMPL:
            ty = field(p, "type")
            tr = field(p, "trait")
            return (
                text(ty, src).split("<")[0].strip() if ty else "",
                [text(tr, src).split("<")[0].strip()] if tr else [],
            )
        if p.type in RS.TYPE_DECLS:
            n = field(p, "name")
            return (text(n, src) if n else "", [])
        p = p.parent
    return "", []


def _collect_calls(fn_node: Node, src: bytes) -> list[str]:
    calls: list[str] = []
    for n in walk(fn_node):
        if n.type != RS.CALL:
            continue
        target = field(n, "function") or (n.children[0] if n.children else None)
        if target is not None:
            calls.append(text(target, src).strip())
    return calls


def _modifiers(node: Node, src: bytes) -> list[str]:
    mods: list[str] = []
    for c in node.children:
        t = text(c, src).strip()
        if c.type == "visibility_modifier":
            mods.append(t)
        elif t in ("async", "unsafe", "const"):
            mods.append(t)
        elif c.type == "function_modifiers" or t.startswith("extern"):
            mods.append("extern")
    return mods


def extract_rust(abs_path: Path, rel_path: str) -> RawFile:
    src = abs_path.read_bytes()
    src_text = src.decode("utf8", errors="replace")
    res = parse(src, "rust")

    rf = RawFile(
        path=rel_path,
        lang="rust",
        module=module_path(rel_path),
        parse_error=res.had_error,
    )

    for node in walk(res.root):
        if node.type == RS.USE:
            raw = text(node, src).strip().removeprefix("use ").rstrip(";")
            if "{" in raw:
                base, _, rest = raw.partition("{")
                for leaf in rest.rstrip("}").split(","):
                    leaf = leaf.strip()
                    if leaf:
                        rf.imports.append(base.strip() + leaf)
            else:
                rf.imports.append(raw)

        elif node.type in RS.TYPE_DECLS:
            name_node = field(node, "name")
            if name_node is None:
                continue
            qualifier, _ = _impl_qualifier(node, src)
            rf.decls.append(
                RawDecl(
                    kind="type",
                    name=text(name_node, src),
                    start_line=_line(node),
                    end_line=node.end_point[0] + 1,
                    source=text(node, src),
                    qualifier=qualifier,
                    annotations=_attributes_above(node, src),
                    modifiers=_modifiers(node, src),
                    doc=_doc_above(src_text, _line(node)),
                )
            )

        elif node.type == RS.FUNCTION:
            name_node = field(node, "name")
            if name_node is None:
                continue
            qualifier, supertypes = _impl_qualifier(node, src)
            params = field(node, "parameters")
            param_types = (
                [text(p, src).split(":")[-1].strip() for p in params.children if p.type == "parameter"]
                if params is not None
                else []
            )
            ret = field(node, "return_type")
            rf.decls.append(
                RawDecl(
                    kind="function",
                    name=text(name_node, src),
                    start_line=_line(node),
                    end_line=node.end_point[0] + 1,
                    source=text(node, src),
                    qualifier=qualifier,
                    annotations=_attributes_above(node, src),
                    modifiers=_modifiers(node, src),
                    param_types=param_types,
                    return_type=text(ret, src).strip() if ret else "",
                    supertypes=supertypes,
                    doc=_doc_above(src_text, _line(node)),
                    calls=_collect_calls(node, src),
                )
            )

    return rf
