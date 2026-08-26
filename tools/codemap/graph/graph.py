"""Graph container.

Deliberately free of analysis logic. Layers, KMP classification, call
resolution and bridge stitching are separate passes that decorate this
structure, which is what lets each of them be tested on its own.
"""
from __future__ import annotations

import subprocess
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


@dataclass
class Node:
    id: str
    kind: str                       # module | file | type | function
    name: str
    lang: str
    file: str
    start_line: int = 0
    end_line: int = 0
    content_hash: str = ""
    parent: str | None = None
    layer: str = "unknown"
    kmp: str = "unknown"            # green | amber | red | unknown
    annotations: list[str] = field(default_factory=list)
    modifiers: list[str] = field(default_factory=list)
    signature: str = ""
    doc: str = ""
    bridge_symbol: str = ""
    kmp_reason: str = ""        # human-readable cause of this node's class
    kmp_via: str = ""           # node id this was inherited from, if propagated
    issues: list[str] = field(default_factory=list)


@dataclass
class Edge:
    src: str
    dst: str
    kind: str                       # contains | imports | calls | bridges |
                                    # injects | persists | navigates | implements
    confidence: str = "exact"       # exact | heuristic


class Graph:
    def __init__(self) -> None:
        self.nodes: dict[str, Node] = {}
        self.edges: list[Edge] = []
        self.stats: dict[str, Any] = {}
        self._edge_index: set[tuple[str, str, str]] = set()
        self._children: dict[str, list[str]] = defaultdict(list)

    # -- construction -----------------------------------------------------

    def add_node(self, node: Node) -> Node:
        existing = self.nodes.get(node.id)
        if existing is not None:
            return existing
        self.nodes[node.id] = node
        if node.parent:
            self._children[node.parent].append(node.id)
        return node

    def add_edge(self, src: str, dst: str, kind: str, confidence: str = "exact") -> None:
        key = (src, dst, kind)
        if key in self._edge_index:
            return
        if src not in self.nodes or dst not in self.nodes:
            return
        self._edge_index.add(key)
        self.edges.append(Edge(src, dst, kind, confidence))

    # -- queries ----------------------------------------------------------

    def parent_of(self, node_id: str) -> Node | None:
        p = self.nodes[node_id].parent
        return self.nodes.get(p) if p else None

    def children_of(self, node_id: str) -> list[Node]:
        return [self.nodes[c] for c in self._children.get(node_id, [])]

    def find_all(self, **criteria: Any) -> list[Node]:
        return [
            n for n in self.nodes.values()
            if all(getattr(n, k) == v for k, v in criteria.items())
        ]

    def find_one(self, **criteria: Any) -> Node:
        hits = self.find_all(**criteria)
        if not hits:
            raise KeyError(f"no node matching {criteria}")
        return hits[0]

    def has_edge(self, src: str, dst: str, kind: str) -> bool:
        return (src, dst, kind) in self._edge_index

    def get_edge(self, src: str, dst: str, kind: str) -> Edge:
        for e in self.edges:
            if (e.src, e.dst, e.kind) == (src, dst, kind):
                return e
        raise KeyError(f"no {kind} edge {src} -> {dst}")

    # -- serialization ----------------------------------------------------

    def to_dict(self, root: Path | None = None) -> dict[str, Any]:
        return {
            "nodes": [vars(n) for n in self.nodes.values()],
            "edges": [vars(e) for e in self.edges],
            "stats": {
                "nodes_by_kind": dict(Counter(n.kind for n in self.nodes.values())),
                "nodes_by_layer": dict(Counter(n.layer for n in self.nodes.values())),
                "nodes_by_kmp": dict(Counter(n.kmp for n in self.nodes.values())),
                "edges_by_kind": dict(Counter(e.kind for e in self.edges)),
                **self.stats,
            },
            "meta": {
                "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "git_sha": _git_sha(root),
                "tool_version": "0.1.0",
            },
        }


def _git_sha(root: Path | None) -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=root or Path.cwd(), capture_output=True, text=True, timeout=5,
        ).stdout.strip() or "unknown"
    except Exception:
        return "unknown"
