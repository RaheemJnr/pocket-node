"""Dependency cycle detection (Tarjan's strongly-connected components).

A cycle between types is a genuine architectural smell: neither member
can be understood, tested, or moved without the other. Reported as
information rather than a CI failure, since breaking one is a judgement
call, not an emergency.
"""
from __future__ import annotations

from .graph import Graph

CYCLE_EDGES = ("calls", "injects")


def find_cycles(g: Graph, kind: str = "type") -> list[list[str]]:
    """SCCs larger than one node, at the requested node kind."""
    nodes = {i for i, n in g.nodes.items() if n.kind == kind}

    parent = {i: n.parent for i, n in g.nodes.items()}

    def lift(nid: str) -> str | None:
        cur = nid
        while cur and cur not in nodes:
            cur = parent.get(cur)
        return cur

    adj: dict[str, set[str]] = {i: set() for i in nodes}
    for e in g.edges:
        if e.kind not in CYCLE_EDGES:
            continue
        s, t = lift(e.src), lift(e.dst)
        if s and t and s != t:
            adj[s].add(t)

    index: dict[str, int] = {}
    low: dict[str, int] = {}
    on_stack: set[str] = set()
    stack: list[str] = []
    counter = [0]
    out: list[list[str]] = []

    def strongconnect(v: str) -> None:
        # iterative, so a deep graph cannot blow the Python stack
        work = [(v, iter(sorted(adj[v])))]
        index[v] = low[v] = counter[0]
        counter[0] += 1
        stack.append(v)
        on_stack.add(v)
        while work:
            node, it = work[-1]
            advanced = False
            for w in it:
                if w not in index:
                    index[w] = low[w] = counter[0]
                    counter[0] += 1
                    stack.append(w)
                    on_stack.add(w)
                    work.append((w, iter(sorted(adj[w]))))
                    advanced = True
                    break
                if w in on_stack:
                    low[node] = min(low[node], index[w])
            if advanced:
                continue
            work.pop()
            if work:
                low[work[-1][0]] = min(low[work[-1][0]], low[node])
            if low[node] == index[node]:
                comp = []
                while True:
                    w = stack.pop()
                    on_stack.discard(w)
                    comp.append(w)
                    if w == node:
                        break
                if len(comp) > 1:
                    out.append(sorted(comp))

    for v in sorted(nodes):
        if v not in index:
            strongconnect(v)

    return sorted(out, key=len, reverse=True)


def format_cycles(g: Graph, cycles: list[list[str]], limit: int = 15) -> str:
    if not cycles:
        return "No dependency cycles found."
    out = [f"{len(cycles)} dependency cycle(s):", ""]
    for comp in cycles[:limit]:
        names = [g.nodes[i].name for i in comp]
        out.append(f"  [{len(comp)}] {' ↔ '.join(names[:6])}"
                   + (f" … +{len(names) - 6}" if len(names) > 6 else ""))
        out.append(f"        {g.nodes[comp[0]].file}")
    if len(cycles) > limit:
        out.append(f"  … {len(cycles) - limit} more")
    return "\n".join(out)
