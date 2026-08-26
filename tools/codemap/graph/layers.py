"""Architectural layer assignment from a rules file.

Edge direction convention, stated once because it is easy to invert:
an edge points from CALLER to CALLEE. Dependencies flow from higher
`order` to lower, so UI (5) depending on data (2) is normal. A backward
edge is a lower-order layer depending on a higher-order one -- data
reaching up into UI -- and those are the layering violations drawn red.
"""
from __future__ import annotations

from fnmatch import fnmatch
from pathlib import Path

import yaml

from .graph import Graph

_RULES: list[dict] = []
_ORDER: dict[str, int] = {}
_LEAF: set[str] = set()


def load_rules(path: Path) -> list[dict]:
    global _RULES, _ORDER, _LEAF
    _RULES = yaml.safe_load(path.read_text())["layers"]
    _ORDER = {r["id"]: r["order"] for r in _RULES}
    _LEAF = {r["id"] for r in _RULES if r.get("leaf")}
    return _RULES


def assign_layer(rel_path: str) -> str:
    for rule in _RULES:
        if any(fnmatch(rel_path, ex) for ex in rule.get("exclude", [])):
            continue
        if any(fnmatch(rel_path, m) for m in rule["match"]):
            return rule["id"]
    return "unknown"


def layer_order(layer: str) -> int:
    return _ORDER.get(layer, -1)


def is_backward_edge(src_layer: str, dst_layer: str) -> bool:
    """A lower-order layer depending on a higher-order one.

    Layers marked `leaf` in the rules are exempt: a leaf utility is
    something every tier may call, so depending on it is never a
    violation regardless of where its lane sits.
    """
    if dst_layer in _LEAF:
        return False
    a, b = layer_order(src_layer), layer_order(dst_layer)
    if a < 0 or b < 0:
        return False
    return a < b


def apply(g: Graph, rules_path: Path) -> None:
    load_rules(rules_path)
    for n in g.nodes.values():
        if n.file:
            n.layer = assign_layer(n.file)
    # modules have no file of their own -- inherit from their children
    for n in g.nodes.values():
        if n.kind == "module":
            kids = [c.layer for c in g.children_of(n.id) if c.layer != "unknown"]
            if kids:
                n.layer = max(set(kids), key=kids.count)

    g.stats["layer_meta"] = {
        r["id"]: {"order": r["order"], "label": r["label"], "leaf": bool(r.get("leaf"))}
        for r in _RULES
    }
    g.stats["violations"] = sum(
        1 for e in g.edges
        if e.kind in ("calls", "injects")
        and is_backward_edge(g.nodes[e.src].layer, g.nodes[e.dst].layer)
    )
