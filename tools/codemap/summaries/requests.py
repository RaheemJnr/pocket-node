"""Round trip for generated summaries.

Deliberately a FILE exchange, not an API call: the tool itself never
touches the network, so the offline guarantee holds and the workflow is
not coupled to any particular model or service. Anything -- an
assistant, a script, a person -- can fill in the responses file.
"""
from __future__ import annotations

import json
from pathlib import Path

from .store import SummaryStore

MAX_SOURCE_LINES = 40


def emit_requests(data: dict, root: Path, store: SummaryStore) -> list[dict]:
    """One record per absent-or-stale node that has a body worth describing."""
    out = []
    for n in data["nodes"]:
        if n["kind"] not in ("type", "function"):
            continue
        if not n.get("content_hash"):
            continue
        if store.get(n["id"], n["content_hash"]).state == "current":
            continue
        src = ""
        path = root / n["file"]
        if path.exists() and n.get("start_line"):
            lines = path.read_text(errors="replace").splitlines()
            src = "\n".join(lines[n["start_line"] - 1 : n["end_line"]][:MAX_SOURCE_LINES])
        out.append({
            "id": n["id"], "kind": n["kind"], "name": n["name"],
            "signature": n.get("signature", ""), "file": n["file"],
            "start_line": n.get("start_line", 0), "hash": n["content_hash"],
            "source": src,
        })
    return out


def emit_requests_file(data: dict, root: Path, dest: Path) -> int:
    store = SummaryStore.load(Path(__file__).resolve().parent / "descriptions.json")
    reqs = emit_requests(data, root, store)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text("\n".join(json.dumps(r) for r in reqs))
    return len(reqs)


def import_responses_file(src: Path, store_path: Path, root: Path) -> int:
    from graph.build import build_full_graph
    g = build_full_graph(root)
    id_to_hash = {n.id: n.content_hash for n in g.nodes.values()}

    records = [json.loads(line) for line in src.read_text().splitlines() if line.strip()]
    store = SummaryStore.load(store_path)
    n = store.import_responses(records, id_to_hash)
    store.save(store_path)
    return n
