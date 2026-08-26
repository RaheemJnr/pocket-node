"""Descriptions for file and module nodes, rolled up from their contents.

Files and modules are containers, not declarations, so nothing writes a
summary for them and switching depth to Files used to show an empty card
on every node. These are computed on each run rather than cached, so they
cannot go stale and never need a hash check.
"""
from __future__ import annotations

from .graph import Graph

MAX_NAMES = 4


def _described(node, summaries: dict) -> str:
    if node.doc:
        return node.doc
    rec = summaries.get(node.id)
    if rec and rec.get("hash") == node.content_hash:
        return rec.get("text", "")
    return ""


def describe_file(g: Graph, file_id: str, summaries: dict) -> str:
    kids = g.children_of(file_id)
    types = [k for k in kids if k.kind == "type"]
    funcs = [k for k in kids if k.kind == "function"]

    # A file named after its single main type inherits that type's meaning.
    stem = g.nodes[file_id].name.rsplit(".", 1)[0]
    primary = next((t for t in types if t.name == stem), None)
    if primary is not None:
        text = _described(primary, summaries)
        if text:
            extra = len(types) - 1
            tail = f" Also declares {extra} other type{'s' if extra != 1 else ''}." if extra > 0 else ""
            return f"{text}{tail}"

    if types:
        names = ", ".join(t.name for t in types[:MAX_NAMES])
        more = f" and {len(types) - MAX_NAMES} more" if len(types) > MAX_NAMES else ""
        return f"Declares {names}{more}."
    if funcs:
        names = ", ".join(f.name for f in funcs[:MAX_NAMES])
        more = f" and {len(funcs) - MAX_NAMES} more" if len(funcs) > MAX_NAMES else ""
        return f"Top-level functions: {names}{more}."
    return ""


def describe_module(g: Graph, module_id: str, summaries: dict) -> str:
    files = g.children_of(module_id)
    types: list[str] = []
    for f in files:
        types += [k.name for k in g.children_of(f.id) if k.kind == "type"]
    if not files:
        return ""
    head = f"{len(files)} file{'s' if len(files) != 1 else ''}"
    if types:
        names = ", ".join(types[:MAX_NAMES])
        more = f" and {len(types) - MAX_NAMES} more" if len(types) > MAX_NAMES else ""
        return f"{head}, declaring {names}{more}."
    return f"{head}."


def apply(g: Graph, summaries: dict) -> int:
    """Add derived entries for container nodes. Returns how many were added."""
    added = 0
    for node in g.nodes.values():
        if node.kind not in ("file", "module") or node.id in summaries:
            continue
        text = (describe_file(g, node.id, summaries) if node.kind == "file"
                else describe_module(g, node.id, summaries))
        if text:
            # hash matches the node's own, so it always reads as current
            summaries[node.id] = {"text": text, "hash": node.content_hash, "derived": True}
            added += 1
    return added
