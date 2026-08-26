"""Structural invariants, for CI.

Each check returns a list of failure strings; empty means pass. They are
deliberately conservative: only assert things that would be a genuine
regression, never style preferences, so a red build always means
something actually broke.
"""
from __future__ import annotations

from .graph import Graph
from .layers import is_backward_edge


def check_bridges_paired(g: Graph) -> list[str]:
    out = []
    b = g.stats.get("bridge", {})
    for sym in b.get("kotlin_orphans", []):
        out.append(f"JNI: Kotlin `external fun` has no Rust implementation: {sym}")
    for sym in b.get("rust_orphans", []):
        out.append(f"JNI: Rust symbol has no Kotlin declaration: {sym}")
    u = g.stats.get("uniffi", {})
    for sym in u.get("swift_orphans", []):
        out.append(f"UniFFI: Swift calls a binding with no Rust export: {sym}")
    return out


def check_no_layer_violations(g: Graph) -> list[str]:
    out = []
    for e in g.edges:
        if e.kind not in ("calls", "injects"):
            continue
        s, t = g.nodes[e.src], g.nodes[e.dst]
        if is_backward_edge(s.layer, t.layer):
            out.append(f"Layering: {s.layer} -> {t.layer}: {s.name} {e.kind} {t.name} ({s.file})")
    return out


def check_parse_errors(g: Graph, allow: set[str]) -> list[str]:
    return [
        f"Parse error in a file not on the allow-list: {p}"
        for p in g.stats.get("parse_errors", [])
        if p not in allow
    ]


def check_unresolved_rate(g: Graph, ceiling: float) -> list[str]:
    rate = g.stats.get("unresolved_calls", {}).get("rate", 0.0)
    if rate > ceiling:
        return [f"Call resolution degraded: {rate:.1%} in-scope failures, ceiling {ceiling:.1%}"]
    return []


def run_all(g: Graph, config: dict) -> dict[str, list[str]]:
    return {
        "bridges_paired": check_bridges_paired(g),
        "no_layer_violations": check_no_layer_violations(g),
        "parse_errors": check_parse_errors(g, set(config.get("allow_parse_errors", []))),
        "unresolved_rate": check_unresolved_rate(g, config.get("unresolved_ceiling", 0.25)),
    }
