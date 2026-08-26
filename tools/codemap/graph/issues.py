"""Link nodes to the issues discussed in their comments.

This codebase references issues heavily in comments (#332, #424, ...).
Attaching them to nodes turns the map into something navigable back to
the reasoning behind a decision.
"""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

from extract.model import RawFile

from .graph import Graph
from .ids import function_id, type_id

# `#123` but never a hex colour, a fragment, or a markdown heading
ISSUE_RE = re.compile(r"(?<![\w&#])#(\d{1,5})(?!\w)")


def repo_url(root: Path) -> str:
    try:
        url = subprocess.run(["git", "remote", "get-url", "origin"], cwd=root,
                             capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        return ""
    if url.startswith("git@github.com:"):
        url = "https://github.com/" + url.split(":", 1)[1]
    return url.removesuffix(".git")


def find_issues(text: str) -> list[str]:
    return sorted({m.group(1) for m in ISSUE_RE.finditer(text or "")}, key=int)


def apply(g: Graph, files: list[RawFile], root: Path) -> None:
    total = 0
    for rf in files:
        for d in rf.decls:
            nid = (type_id(rf.lang, rf.module, d.name) if d.kind == "type"
                   else function_id(rf.lang, rf.module, d.qualifier, d.name, len(d.param_types)))
            node = g.nodes.get(nid)
            if node is None:
                continue
            found = find_issues(d.doc) or find_issues(d.source[:600])
            if found:
                node.issues = found
                total += len(found)
    g.stats["issues"] = {"linked": total, "repo_url": repo_url(root)}
