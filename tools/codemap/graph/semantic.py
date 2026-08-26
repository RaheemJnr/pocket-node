"""Exact semantic edges: Hilt injection, Room persistence, navigation.

Named `semantic` rather than `annotations` to avoid colliding with the
`from __future__ import annotations` name binding in importing modules.
"""
from __future__ import annotations

from extract.model import RawFile

from .graph import Graph
from .ids import type_id

INJECT_MARKERS = {"Inject", "HiltViewModel", "Singleton", "Provides"}


def apply(g: Graph, files: list[RawFile]) -> None:
    types_by_name: dict[str, list[str]] = {}
    for rf in files:
        for d in rf.decls:
            if d.kind == "type":
                types_by_name.setdefault(d.name, []).append(type_id(rf.lang, rf.module, d.name))

    injects = persists = 0

    for rf in files:
        for d in rf.decls:
            if d.kind != "type":
                continue
            tid = type_id(rf.lang, rf.module, d.name)
            if tid not in g.nodes:
                continue

            if INJECT_MARKERS & set(d.annotations):
                for pt in d.param_types:
                    for target in types_by_name.get(pt, []):
                        if target != tid:
                            g.add_edge(tid, target, "injects")
                            injects += 1

            if "Dao" in d.annotations or "Entity" in d.annotations:
                for pt in d.param_types:
                    for target in types_by_name.get(pt, []):
                        g.add_edge(tid, target, "persists")
                        persists += 1

    g.stats["semantic"] = {"injects": injects, "persists": persists}
