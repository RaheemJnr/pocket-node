"""Migration planner.

Answers "I want X in commonMain -- what has to move first, and in what
order?" by walking what X depends on, ordering it topologically, and
stopping at each Android-locked leaf with the reason it is stuck.

The output is a work queue, not a diagnosis. Every blocker carries its
cause so no claim has to be verified by hand.
"""
from __future__ import annotations

from .explain import explain_kmp
from .graph import Graph

DEPENDENCY_EDGES = ("calls", "injects", "bridges")


def _dependencies(g: Graph) -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for e in g.edges:
        if e.kind in DEPENDENCY_EDGES:
            out.setdefault(e.src, set()).add(e.dst)
    return out


def plan(g: Graph, target_id: str, max_nodes: int = 400) -> dict:
    """Ordered extraction plan for moving `target_id` to commonMain.

    A type target expands to include its own members: moving a class
    means moving its methods, and it is the per-method classification
    that tells you what is actually stuck.
    """
    deps = _dependencies(g)

    seeds = [target_id]
    if g.nodes[target_id].kind == "type":
        seeds += [c.id for c in g.children_of(target_id)]

    # everything the target transitively needs, own-code only
    reachable: set[str] = set(seeds[1:])
    stack = list(seeds)
    while stack and len(reachable) < max_nodes:
        cur = stack.pop()
        for nxt in deps.get(cur, ()):
            if nxt in reachable:
                continue
            node = g.nodes.get(nxt)
            if node is None or node.lang not in ("kotlin", "rust", "swift"):
                continue
            reachable.add(nxt)
            if node.lang == "kotlin":
                stack.append(nxt)

    scope = reachable | {target_id}
    kotlin = {i for i in scope if g.nodes[i].lang == "kotlin"}

    # depth-first post-order gives dependencies before dependents
    order: list[str] = []
    seen: set[str] = set()

    def visit(nid: str, path: set[str]) -> None:
        if nid in seen or nid in path:
            return
        path = path | {nid}
        for d in sorted(deps.get(nid, ())):
            if d in kotlin:
                visit(d, path)
        seen.add(nid)
        order.append(nid)

    for s in seeds:
        visit(s, set())

    ready, needs_seam, blocked = [], [], []
    for nid in order:
        n = g.nodes[nid]
        row = {
            "id": nid, "name": n.name, "kind": n.kind, "file": n.file,
            "line": n.start_line, "kmp": n.kmp,
            "reason": n.kmp_reason, "layer": n.layer,
        }
        (ready if n.kmp == "green" else needs_seam if n.kmp == "amber" else blocked).append(row)

    blockers = [explain_kmp(g, r["id"]) for r in blocked]

    return {
        "target": {"id": target_id, "name": g.nodes[target_id].name,
                   "kmp": g.nodes[target_id].kmp},
        "order": order,
        "ready": ready,
        "needs_seam": needs_seam,
        "blocked": blocked,
        "blockers": blockers,
        "rust_dependencies": sorted(
            g.nodes[i].name for i in scope if g.nodes[i].lang == "rust"),
        "truncated": len(reachable) >= max_nodes,
    }


def format_plan(p: dict) -> str:
    t = p["target"]
    out = [f"Extraction plan for {t['name']}  (currently {t['kmp']})", ""]
    out.append(f"  {len(p['ready'])} ready · {len(p['needs_seam'])} need expect/actual · "
               f"{len(p['blocked'])} blocked")
    if p["truncated"]:
        out.append("  (dependency set truncated -- target is very widely connected)")

    def section(title, rows, limit=25):
        if not rows:
            return
        out.append("")
        out.append(f"{title} ({len(rows)})")
        for r in rows[:limit]:
            out.append(f"  {r['name']:<38} {r['file'].split('/')[-1]}:{r['line']}")
        if len(rows) > limit:
            out.append(f"  … {len(rows) - limit} more")

    section("MOVE FIRST -- already commonMain-ready", p["ready"])
    section("THEN -- needs an expect/actual seam", p["needs_seam"])

    if p["blocked"]:
        out.append("")
        out.append(f"BLOCKED -- androidMain only ({len(p['blocked'])})")
        for b in p["blockers"][:15]:
            head = b["chain"][0]
            out.append(f"  {head['name']:<38} {head['reason']}")
        if len(p["blockers"]) > 15:
            out.append(f"  … {len(p['blockers']) - 15} more")

    if p["rust_dependencies"]:
        out.append("")
        out.append(f"Rust reached through the bridge: {', '.join(p['rust_dependencies'][:10])}")
    return "\n".join(out)
