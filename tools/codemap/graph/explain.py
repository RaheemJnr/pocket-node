"""Why is this node classified the way it is?

The overlay says a node is red; this says which import, marker, or call
chain made it so. Without that, every planner claim has to be verified by
hand, and a tool nobody trusts stops getting used.
"""
from __future__ import annotations

from .graph import Graph

MAX_CHAIN = 8


def explain_kmp(g: Graph, node_id: str) -> dict:
    """Reason for a node's KMP class, following propagation to its root."""
    node = g.nodes.get(node_id)
    if node is None:
        return {}

    chain: list[dict] = []
    seen = {node_id}
    cur = node
    while True:
        chain.append({
            "id": cur.id,
            "name": cur.name,
            "kmp": cur.kmp,
            "reason": cur.kmp_reason,
            "file": cur.file,
            "line": cur.start_line,
        })
        nxt = g.nodes.get(cur.kmp_via) if cur.kmp_via else None
        if nxt is None or nxt.id in seen or len(chain) >= MAX_CHAIN:
            break
        seen.add(nxt.id)
        cur = nxt

    root = chain[-1]
    return {
        "kmp": node.kmp,
        "summary": _summary(node, chain),
        "chain": chain,
        "root_cause": root,
        "blocked_by_self": len(chain) == 1,
    }


def _summary(node, chain: list[dict]) -> str:
    if node.kmp == "green":
        return "Nothing platform-specific here; it can move to commonMain as-is."
    if len(chain) == 1:
        return f"{node.name} {node.kmp_reason}."
    hops = " → ".join(c["name"] for c in chain)
    root = chain[-1]
    return f"{hops}. The root cause is {root['name']}, which {root['reason']}."
