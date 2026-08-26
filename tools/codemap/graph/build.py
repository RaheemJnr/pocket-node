"""Turn RawFile[] into a Graph, then apply the decoration passes.

`build_full_graph` is the single entry point used by the CLI, the tests
and every ad-hoc script. Passes are applied in dependency order: nodes
must exist before edges, and calls must be resolved before KMP
propagation can follow them.
"""
from __future__ import annotations

from pathlib import Path

from extract.kotlin import extract_kotlin
from extract.model import RawFile
from extract.rust import extract_rust
from extract.swift import extract_swift
from extract.ts_api import EXT_TO_LANG

from .graph import Graph, Node
from .ids import file_id, function_id, module_id, type_id

KOTLIN_GLOBS = ["android/**/*.kt"]
RUST_GLOBS = ["external/**/*.rs"]
SWIFT_GLOBS = ["ios/**/*.swift"]
EXCLUDE_MARKERS = ("/build/", "/target/", "/.venv/", "/DerivedData/")


def _signature(decl) -> str:
    params = ", ".join(decl.param_types)
    ret = f": {decl.return_type}" if decl.return_type else ""
    return f"{decl.name}({params}){ret}"


def build_graph(files: list[RawFile]) -> Graph:
    """Nodes plus containment edges. No analysis."""
    g = Graph()

    for rf in files:
        mid = module_id(rf.lang, rf.module)
        g.add_node(Node(id=mid, kind="module", name=rf.module or "(root)",
                        lang=rf.lang, file="", parent=None))

        fid = file_id(rf.path)
        g.add_node(Node(id=fid, kind="file", name=Path(rf.path).name,
                        lang=rf.lang, file=rf.path, parent=mid))
        g.add_edge(mid, fid, "contains")

        type_ids: dict[str, str] = {}
        for d in (d for d in rf.decls if d.kind == "type"):
            tid = type_id(rf.lang, rf.module, d.name)
            type_ids[d.name] = tid
            g.add_node(Node(id=tid, kind="type", name=d.name, lang=rf.lang,
                            file=rf.path, start_line=d.start_line,
                            end_line=d.end_line, content_hash=d.content_hash,
                            parent=fid, annotations=list(d.annotations),
                            modifiers=list(d.modifiers), signature=d.name,
                            doc=d.doc))
            g.add_edge(fid, tid, "contains")

        for d in (d for d in rf.decls if d.kind == "function"):
            nid = function_id(rf.lang, rf.module, d.qualifier, d.name, len(d.param_types))
            parent = type_ids.get(d.qualifier, fid)
            g.add_node(Node(id=nid, kind="function", name=d.name, lang=rf.lang,
                            file=rf.path, start_line=d.start_line,
                            end_line=d.end_line, content_hash=d.content_hash,
                            parent=parent, annotations=list(d.annotations),
                            modifiers=list(d.modifiers),
                            signature=_signature(d), doc=d.doc))
            g.add_edge(parent, nid, "contains")

    return g


def discover(root: Path) -> list[Path]:
    """Source files under the configured roots.

    Falls back to scanning the whole tree when the configured globs match
    nothing, so the tool works when pointed at an arbitrary directory --
    which is also what makes the fixture-based CLI tests meaningful
    rather than silently passing on an empty graph.
    """
    out: list[Path] = []
    for globs in (KOTLIN_GLOBS, RUST_GLOBS, SWIFT_GLOBS):
        for pattern in globs:
            for p in root.glob(pattern):
                if any(m in str(p) for m in EXCLUDE_MARKERS):
                    continue
                if p.suffix in EXT_TO_LANG:
                    out.append(p)
    if not out:
        for p in root.rglob("*"):
            if any(m in str(p) for m in EXCLUDE_MARKERS):
                continue
            if p.suffix in EXT_TO_LANG:
                out.append(p)
    return sorted(set(out))


def extract_all(root: Path, paths: list[Path] | None = None) -> list[RawFile]:
    files: list[RawFile] = []
    for p in paths if paths is not None else discover(root):
        rel = str(p.relative_to(root))
        try:
            if p.suffix in (".kt", ".kts"):
                if "test" in rel.lower():
                    continue
                files.append(extract_kotlin(p, rel))
            elif p.suffix == ".rs":
                files.append(extract_rust(p, rel))
            elif p.suffix == ".swift":
                files.append(extract_swift(p, rel))
        except Exception as exc:  # one bad file must not abort the run
            files.append(RawFile(path=rel, lang="unknown", module="", parse_error=True))
            print(f"  warning: {rel}: {exc}")
    return files


def build_full_graph(root: Path, rules_dir: Path | None = None) -> Graph:
    """Extract, build, and apply every decoration pass available."""
    from . import semantic as semantic_pass
    from . import bridge as bridge_pass
    from . import calls as calls_pass
    from . import kmp as kmp_pass
    from . import layers as layers_pass

    rules = rules_dir or (Path(__file__).resolve().parent.parent / "rules")
    files = extract_all(root)
    g = build_graph(files)

    g.stats["parse_errors"] = [f.path for f in files if f.parse_error]

    bridge_pass.apply(g, files)
    bridge_pass.apply_uniffi(g, files)
    calls_pass.apply(g, files)
    semantic_pass.apply(g, files)
    layers_pass.apply(g, rules / "layers.yaml")
    kmp_pass.apply(g, files, rules / "kmp.yaml")
    return g
