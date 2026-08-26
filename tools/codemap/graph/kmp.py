"""KMP readiness classification.

green -- commonMain-ready
amber -- needs expect/actual
red   -- androidMain only

Red is reserved for DIRECT evidence: the declaration itself references an
Android-only API, or carries a marker such as `external` (a JNI call by
definition) or `@Composable`. A clean function that merely calls a red one
becomes amber, because it needs an expect/actual seam rather than being
Android-bound itself. That distinction is the whole point of classifying
at function level instead of file level.

Rust and Swift nodes are left `unknown` -- this is a Kotlin-migration
overlay and colouring Rust would imply a judgement the tool cannot make.
"""
from __future__ import annotations

import re
from pathlib import Path

import yaml

from extract.model import RawFile

from .graph import Graph
from .ids import function_id, type_id

RANK = {"green": 0, "amber": 1, "red": 2}
UNRANK = {v: k for k, v in RANK.items()}


def _classify_ref(ref: str, rules: dict) -> str | None:
    best: tuple[int, str] | None = None
    for cls in ("green", "amber", "red"):
        for prefix in rules.get(cls, []) or []:
            if ref.startswith(prefix) and (best is None or len(prefix) > best[0]):
                best = (len(prefix), cls)
    return best[1] if best else None


def apply(g: Graph, files: list[RawFile], rules_path: Path) -> None:
    rules = yaml.safe_load(rules_path.read_text())
    markers = rules.get("markers", {}) or {}
    red_mods = set(markers.get("red_modifiers", []) or [])
    red_anns = set(markers.get("red_annotations", []) or [])

    # -- stage 1: direct evidence ----------------------------------------
    for rf in files:
        if rf.lang != "kotlin":
            continue

        file_classes = [c for c in (_classify_ref(i, rules) for i in rf.imports) if c]
        # Keyed by the identifier that actually appears in source. For
        # `import a.b.C as D` that is D, not C -- matching only the
        # original final segment misses every aliased usage.
        import_by_simple = {i.rsplit(".", 1)[-1]: i for i in rf.imports}
        import_by_simple.update(rf.aliases)

        for d in rf.decls:
            nid = (
                type_id(rf.lang, rf.module, d.name)
                if d.kind == "type"
                else function_id(rf.lang, rf.module, d.qualifier, d.name, len(d.param_types))
            )
            node = g.nodes.get(nid)
            if node is None:
                continue

            hit_mod = red_mods & set(d.modifiers)
            hit_ann = red_anns & set(d.annotations)
            if hit_mod or hit_ann:
                node.kmp = "red"
                node.kmp_reason = (
                    f"declared `{sorted(hit_mod)[0]}`" if hit_mod
                    else f"annotated `@{sorted(hit_ann)[0]}`"
                )
                continue

            # Only what THIS declaration references. `local_types` is the
            # call-resolution scope and includes the enclosing class's
            # fields, so using it here would paint every method of a
            # Context-holding class red -- which is exactly the conflation
            # function-level extraction exists to avoid.
            worst = 0
            cause = ""
            declared = set(d.param_types) | set(d.supertypes) | set(d.annotations)
            for name in declared:
                fqn = import_by_simple.get(name)
                if fqn:
                    cls = _classify_ref(fqn, rules)
                    if cls and RANK[cls] > worst:
                        worst, cause = RANK[cls], f"references `{fqn}` in its signature"
            for simple, fqn in import_by_simple.items():
                if simple in declared:
                    continue
                if _mentions(d.source, simple):
                    cls = _classify_ref(fqn, rules)
                    if cls and RANK[cls] > worst:
                        worst, cause = RANK[cls], f"uses `{fqn}`"
            node.kmp = UNRANK[worst]
            node.kmp_reason = cause or "references nothing platform-specific"

    # types with no direct evidence inherit the file's worst import class,
    # since a class holding a Context is Android-bound even if the property
    # type resolution missed it
    for rf in files:
        if rf.lang != "kotlin":
            continue
        file_worst = max(
            (RANK[c] for c in (_classify_ref(i, rules) for i in rf.imports) if c),
            default=0,
        )
        for d in rf.decls:
            if d.kind != "type":
                continue
            node = g.nodes.get(type_id(rf.lang, rf.module, d.name))
            if node is not None and RANK.get(node.kmp, 0) < file_worst:
                if file_worst == RANK["red"] and _touches(d, rf, rules):
                    node.kmp = "red"
                    if not node.kmp_reason:
                        node.kmp_reason = "holds a platform-specific type"

    # -- stage 2: propagation --------------------------------------------
    if not rules.get("propagate"):
        _record(g)
        return

    outgoing: dict[str, list[str]] = {}
    for e in g.edges:
        if e.kind in ("calls", "bridges"):
            outgoing.setdefault(e.src, []).append(e.dst)

    for _ in range(20):
        changed = False
        for src, dsts in outgoing.items():
            node = g.nodes.get(src)
            if node is None or node.lang != "kotlin" or node.kmp == "red":
                continue
            for dst in dsts:
                target = g.nodes.get(dst)
                if target is None:
                    continue
                if target.kmp in ("amber", "red") and node.kmp == "green":
                    node.kmp = "amber"
                    node.kmp_reason = f"calls `{target.name}`, which is {target.kmp}"
                    node.kmp_via = target.id
                    changed = True
                elif target.lang == "rust" and node.kmp == "green":
                    node.kmp = "amber"
                    node.kmp_reason = f"crosses the bridge into Rust via `{target.name}`"
                    node.kmp_via = target.id
                    changed = True
        if not changed:
            break

    _record(g)


def _mentions(source: str, simple_name: str) -> bool:
    """Whole-word occurrence of a type's simple name in a declaration's source."""
    return re.search(rf"\b{re.escape(simple_name)}\b", source) is not None


def _touches(decl, rf: RawFile, rules: dict) -> bool:
    """Does this declaration's own source mention a red import's simple name?"""
    for imp in rf.imports:
        if _classify_ref(imp, rules) != "red":
            continue
        if _mentions(decl.source, imp.rsplit(".", 1)[-1]):
            return True
    return False


def _record(g: Graph) -> None:
    kt = [n for n in g.nodes.values() if n.lang == "kotlin" and n.kind == "function"]
    from collections import Counter
    g.stats["kmp"] = dict(Counter(n.kmp for n in kt))
