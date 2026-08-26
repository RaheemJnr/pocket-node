#!/usr/bin/env python3
"""Codemap CLI.

Argument parsing and stage sequencing only. All logic lives in the
extract/, graph/, render/ and summaries/ packages -- if this file grows
past ~150 lines, logic has leaked into it.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from graph.build import build_full_graph, discover  # noqa: E402
from summaries.store import SummaryStore  # noqa: E402


def _report(g, elapsed: float) -> None:
    s = g.to_dict()["stats"]
    uc = s.get("unresolved_calls", {})
    print(f"  built in {elapsed:.1f}s")
    print(f"  nodes  {s['nodes_by_kind']}")
    print(f"  edges  {s['edges_by_kind']}")
    b = s.get("bridge", {})
    print(f"  bridge {b.get('paired', 0)} paired, "
          f"{len(b.get('kotlin_orphans', []))} kotlin orphans, "
          f"{len(b.get('rust_orphans', []))} rust orphans")
    print(f"  calls  {uc.get('resolved', 0)} resolved, "
          f"{uc.get('count', 0)} in-scope failures ({uc.get('rate', 0) * 100:.1f}%), "
          f"{uc.get('out_of_scope', 0)} out of scope")
    print(f"  kmp    {s.get('kmp', {})}")
    print(f"  layer violations: {s.get('violations', 0)}")
    errs = s.get("parse_errors", [])
    if errs:
        print(f"  parse errors ({len(errs)}): {', '.join(Path(e).name for e in errs)}")


def build(root: Path, out: Path, write_html: bool = True) -> dict:
    start = time.time()
    g = build_full_graph(root)
    data = g.to_dict(root)

    store = SummaryStore.load(HERE / "summaries" / "descriptions.json")
    data["summaries"] = store.for_graph(data["nodes"])

    out.mkdir(parents=True, exist_ok=True)
    (out / "codemap.json").write_text(json.dumps(data, indent=None, separators=(",", ":")))

    if write_html:
        from render.build import build_html
        (out / "codemap.html").write_text(build_html(data))

    _report(g, time.time() - start)
    return data


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="codemap")
    ap.add_argument("--root", default=str(HERE.parent.parent), help="repo root")
    ap.add_argument("--out", default=str(HERE / "out"), help="output directory")
    ap.add_argument("--stats-only", action="store_true", help="skip HTML generation")
    ap.add_argument("--watch", action="store_true", help="rebuild on source change")
    ap.add_argument("--serve", type=int, metavar="PORT", help="serve out/ on localhost")
    ap.add_argument("--emit-summary-requests", action="store_true")
    ap.add_argument("--import-summaries", metavar="FILE")
    args = ap.parse_args(argv)

    root, out = Path(args.root).resolve(), Path(args.out).resolve()

    if args.import_summaries:
        from summaries.requests import import_responses_file
        n = import_responses_file(Path(args.import_summaries),
                                  HERE / "summaries" / "descriptions.json", root)
        print(f"imported {n} summaries")
        return 0

    data = build(root, out, write_html=not args.stats_only)

    if args.emit_summary_requests:
        from summaries.requests import emit_requests_file
        n = emit_requests_file(data, root, out / "summary-requests.jsonl")
        print(f"wrote {n} summary requests to {out / 'summary-requests.jsonl'}")
        return 0

    if args.serve:
        _serve(out, args.serve)
    if args.watch:
        _watch(root, out, args)
    return 0


def _watch(root: Path, out: Path, args) -> None:
    print("watching for changes (ctrl-c to stop)")
    stamps = {p: p.stat().st_mtime for p in discover(root)}
    try:
        while True:
            time.sleep(1)
            current = {p: p.stat().st_mtime for p in discover(root)}
            if current != stamps:
                time.sleep(0.4)  # let the write burst settle
                stamps = {p: p.stat().st_mtime for p in discover(root)}
                print("\nchange detected, rebuilding")
                build(root, out, write_html=not args.stats_only)
    except KeyboardInterrupt:
        print("\nstopped")


def _serve(out: Path, port: int) -> None:
    import functools
    import http.server
    import threading

    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(out))
    # localhost only -- never bind 0.0.0.0
    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print(f"serving http://127.0.0.1:{port}/codemap.html")


if __name__ == "__main__":
    raise SystemExit(main())
