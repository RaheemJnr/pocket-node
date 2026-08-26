"""Static exports for PRs, docs and reports.

Mermaid rather than an image: it renders natively on GitHub and in this
project's Artifacts, stays diffable in review, and needs no toolchain.
"""
from __future__ import annotations

import hashlib
import re

MAX_NODES = 60

_SAFE = re.compile(r"[^A-Za-z0-9_]")


def _mid(node_id: str) -> str:
    """Mermaid-safe id.

    Truncation alone collides: `...GatewayRepository#refreshBalance/0`
    and `...#refreshBalanceForWallet/2` share their first 60 characters,
    which silently merged two nodes into one in the diagram. A hash of
    the full id keeps it unique and stable across runs.
    """
    digest = hashlib.sha1(node_id.encode("utf8")).hexdigest()[:6]
    return "n" + _SAFE.sub("_", node_id)[:44] + "_" + digest


def to_mermaid(data: dict, focus: str | None = None, depth: int = 1,
               kind: str = "type", max_nodes: int = MAX_NODES) -> str:
    """Mermaid flowchart for a subgraph.

    With `focus`, emits that node's neighbourhood out to `depth` hops --
    the useful unit for a PR description. Without it, emits the graph at
    `kind` level, capped so the output stays readable.
    """
    nodes = {n["id"]: n for n in data["nodes"]}
    edges = [e for e in data["edges"] if e["kind"] != "contains"]

    if focus:
        keep = {focus}
        frontier = {focus}
        for _ in range(max(1, depth)):
            nxt = set()
            for e in edges:
                if e["src"] in frontier:
                    nxt.add(e["dst"])
                if e["dst"] in frontier:
                    nxt.add(e["src"])
            nxt -= keep
            keep |= nxt
            frontier = nxt
    else:
        deg: dict[str, int] = {}
        for e in edges:
            deg[e["src"]] = deg.get(e["src"], 0) + 1
            deg[e["dst"]] = deg.get(e["dst"], 0) + 1
        cands = [i for i, n in nodes.items() if n["kind"] == kind and deg.get(i)]
        cands.sort(key=lambda i: -deg.get(i, 0))
        keep = set(cands[:max_nodes])

    keep = set(list(keep)[:max_nodes])

    by_layer: dict[str, list[str]] = {}
    for i in keep:
        by_layer.setdefault(nodes[i]["layer"], []).append(i)

    meta = data.get("stats", {}).get("layer_meta", {})
    out = ["flowchart LR"]
    for layer, ids in sorted(by_layer.items(),
                             key=lambda kv: meta.get(kv[0], {}).get("order", 99)):
        label = meta.get(layer, {}).get("label", layer)
        out.append(f'  subgraph {_SAFE.sub("_", layer)}["{label}"]')
        for i in sorted(ids, key=lambda x: nodes[x]["name"]):
            out.append(f'    {_mid(i)}["{nodes[i]["name"]}"]')
        out.append("  end")

    seen = set()
    for e in edges:
        if e["src"] not in keep or e["dst"] not in keep:
            continue
        key = (e["src"], e["dst"], e["kind"])
        if key in seen:
            continue
        seen.add(key)
        arrow = "==>" if e["kind"] == "bridges" else "-->"
        label = f'|{e["kind"]}|' if e["kind"] in ("bridges", "injects") else ""
        out.append(f'  {_mid(e["src"])} {arrow}{label} {_mid(e["dst"])}')

    if focus and focus in nodes:
        out.append(f'  style {_mid(focus)} stroke-width:3px')
    return "\n".join(out)
