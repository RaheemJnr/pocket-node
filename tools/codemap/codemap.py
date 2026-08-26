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
    summaries = store.for_graph(data["nodes"])

    # Files and modules are containers, not declarations, so nothing
    # writes summaries for them. Roll them up from their contents.
    from graph.rollup import apply as rollup
    rollup(g, summaries)
    data["summaries"] = summaries

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
    ap.add_argument("--check", action="store_true",
                    help="run structural invariants and exit non-zero on failure (CI)")
    ap.add_argument("--mermaid", metavar="FILE", help="write a Mermaid subgraph")
    ap.add_argument("--focus", metavar="NODE", help="node id or name to centre --mermaid on")
    ap.add_argument("--depth-hops", type=int, default=1, help="hops around --focus")
    ap.add_argument("--plan", metavar="NAME", help="extraction plan for moving NAME to commonMain")
    ap.add_argument("--why", metavar="NAME", help="explain a node's KMP classification")
    ap.add_argument("--cycles", action="store_true", help="report dependency cycles")
    ap.add_argument("--cycle-kind", default="type", choices=["type", "module", "file"])
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

    if args.check:
        return _check(root)

    if args.plan or args.why or args.cycles:
        return _analyse(root, args)

    data = build(root, out, write_html=not args.stats_only or args.mermaid is not None)

    if args.mermaid:
        from render.export import to_mermaid
        focus = args.focus
        if focus and focus not in {n["id"] for n in data["nodes"]}:
            matches = [n["id"] for n in data["nodes"] if n["name"] == focus]
            if not matches:
                print(f"no node named {focus!r}")
                return 2
            focus = matches[0]
        Path(args.mermaid).write_text(to_mermaid(data, focus=focus, depth=args.depth_hops))
        print(f"wrote Mermaid to {args.mermaid}")
        return 0

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


def _resolve(g, name: str):
    hits = [n for n in g.nodes.values() if n.id == name or n.name == name]
    if not hits:
        print(f"no node named {name!r}")
        return None
    hits.sort(key=lambda n: (n.kind != "type", n.name))
    if len(hits) > 1:
        print(f"note: {len(hits)} nodes named {name!r}; using {hits[0].kind} in {hits[0].file}\n")
    return hits[0]


def _analyse(root: Path, args) -> int:
    from graph.cycles import find_cycles, format_cycles
    from graph.explain import explain_kmp
    from graph.planner import format_plan, plan

    g = build_full_graph(root)

    if args.cycles:
        print(format_cycles(g, find_cycles(g, args.cycle_kind)))
    if args.why:
        n = _resolve(g, args.why)
        if n is None:
            return 2
        e = explain_kmp(g, n.id)
        print(f"{n.name}  [{e['kmp']}]  {n.file}:{n.start_line}")
        print(f"  {e['summary']}")
        if len(e["chain"]) > 1:
            print("\n  chain:")
            for step in e["chain"]:
                print(f"    {step['name']:<34} {step['kmp']:<6} {step['reason']}")
    if args.plan:
        n = _resolve(g, args.plan)
        if n is None:
            return 2
        print(format_plan(plan(g, n.id)))
    return 0


def _check(root: Path) -> int:
    """Structural invariants for CI. Prints every failure, then exits."""
    import yaml
    from graph.checks import run_all

    cfg_path = HERE / "rules" / "checks.yaml"
    cfg = yaml.safe_load(cfg_path.read_text()) if cfg_path.exists() else {}
    g = build_full_graph(root)
    results = run_all(g, cfg or {})

    failures = 0
    for name, msgs in results.items():
        if msgs:
            failures += len(msgs)
            print(f"FAIL {name}")
            for m in msgs:
                print(f"       {m}")
        else:
            print(f"ok   {name}")

    b = g.stats.get("bridge", {})
    print(f"\n     JNI pairs {b.get('paired', 0)} · "
          f"unresolved {g.stats.get('unresolved_calls', {}).get('rate', 0):.1%} · "
          f"nodes {len(g.nodes)}")
    if failures:
        print(f"\n{failures} structural check failure(s)")
        return 1
    print("\nall structural checks passed")
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
