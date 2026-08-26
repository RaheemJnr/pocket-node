"""Generated-summary cache with a content-hash staleness guard.

A summary written against one version of a declaration must never be
shown as current for a different one. When the hash stops matching, the
text is still returned so the UI can grey it out and label it stale --
degrading to terse-but-true rather than to confidently describing code
that no longer exists.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date
from pathlib import Path


@dataclass
class Summary:
    state: str          # current | stale | absent
    text: str = ""
    hash: str = ""


class SummaryStore:
    def __init__(self, data: dict | None = None, path: Path | None = None) -> None:
        self._data = data or {}
        self._path = path

    @classmethod
    def load(cls, path: Path) -> "SummaryStore":
        if path.exists():
            return cls(json.loads(path.read_text()), path)
        return cls({}, path)

    def save(self, path: Path | None = None) -> None:
        target = path or self._path
        if target is None:
            raise ValueError("no path to save to")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(self._data, indent=2, sort_keys=True))

    def get(self, node_id: str, current_hash: str) -> Summary:
        rec = self._data.get(node_id)
        if not rec:
            return Summary("absent")
        if rec.get("hash") == current_hash:
            return Summary("current", rec.get("text", ""), rec.get("hash", ""))
        return Summary("stale", rec.get("text", ""), rec.get("hash", ""))

    def import_responses(self, records: list[dict], id_to_hash: dict[str, str]) -> int:
        """Merge, rejecting any record whose hash does not match the graph."""
        n = 0
        for r in records:
            nid, h = r.get("id"), r.get("hash")
            if not nid or not h or id_to_hash.get(nid) != h:
                continue
            self._data[nid] = {"hash": h, "text": r.get("text", ""),
                               "written": date.today().isoformat()}
            n += 1
        return n

    def for_graph(self, nodes: list[dict]) -> dict:
        """Everything the renderer needs, keyed by node id."""
        out = {}
        for n in nodes:
            rec = self._data.get(n["id"])
            if rec:
                out[n["id"]] = {"text": rec.get("text", ""), "hash": rec.get("hash", "")}
        return out

    def __len__(self) -> int:
        return len(self._data)
