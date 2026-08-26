"""Call-edge resolution.

The only heuristic pass in the tool. Without type inference, a call like
`repo.refresh()` is resolved from the declared type of `repo` in scope.
That holds across this codebase because dependencies are
constructor-injected and consistently typed.

Deliberately NOT resolved, because each needs real inference: generic
type parameters, lambda receivers, scope functions that rebind `this`
(`apply`/`let`/`run`/`with`), and extension-function dispatch. Those land
in the unresolved bucket, whose size is the honest signal for whether a
bytecode-grounded pass is worth building.
"""
from __future__ import annotations

from collections import Counter

from extract.model import RawFile

from .graph import Graph
from .ids import function_id, type_id


def _build_tables(files: list[RawFile]) -> tuple[dict, dict, dict]:
    """simple-name -> type id, (type id, method) -> fn id, (module, fn) -> fn id."""
    types_by_name: dict[str, list[str]] = {}
    methods: dict[tuple[str, str], str] = {}
    toplevel: dict[tuple[str, str], str] = {}

    for rf in files:
        for d in rf.decls:
            if d.kind == "type":
                tid = type_id(rf.lang, rf.module, d.name)
                types_by_name.setdefault(d.name, []).append(tid)
            elif d.kind == "function":
                fid = function_id(rf.lang, rf.module, d.qualifier, d.name, len(d.param_types))
                if d.qualifier:
                    owner = type_id(rf.lang, rf.module, d.qualifier)
                    methods[(owner, d.name)] = fid
                else:
                    toplevel[(rf.module, d.name)] = fid
    return types_by_name, methods, toplevel


def apply(g: Graph, files: list[RawFile]) -> None:
    types_by_name, methods, toplevel = _build_tables(files)
    # Every function name declared anywhere in the mapped codebase. A callee
    # not in this set is EXTERNAL (Compose, Kotlin/Rust stdlib, Android SDK) --
    # out of scope by construction, not a resolution failure. Conflating the
    # two makes the quality metric meaningless.
    unresolved: Counter[str] = Counter()
    external: Counter[str] = Counter()
    resolved = 0

    for rf in files:
        for d in rf.decls:
            if d.kind != "function":
                continue
            src_id = function_id(rf.lang, rf.module, d.qualifier, d.name, len(d.param_types))
            if src_id not in g.nodes:
                continue

            own_type = type_id(rf.lang, rf.module, d.qualifier) if d.qualifier else None

            for raw in d.calls:
                target, reason = _resolve(
                    raw, rf, d, own_type, types_by_name, methods, toplevel
                )
                callee = raw.split(".")[-1]
                if target and target in g.nodes and target != src_id:
                    g.add_edge(src_id, target, "calls", confidence="heuristic")
                    resolved += 1
                elif reason == "method_missing":
                    unresolved[callee] += 1
                else:
                    external[callee] += 1

    in_scope = resolved + sum(unresolved.values())
    g.stats["unresolved_calls"] = {
        "count": sum(unresolved.values()),
        "resolved": resolved,
        "out_of_scope": sum(external.values()),
        "rate": round(sum(unresolved.values()) / in_scope, 4) if in_scope else 0.0,
        "top": unresolved.most_common(25),
        "top_out_of_scope": external.most_common(15),
    }


def _resolve(raw, rf, decl, own_type, types_by_name, methods, toplevel):
    """Return (node_id, reason).

    reason is one of:
      hit             -- resolved
      no_receiver     -- receiver's type is unknown, so the callee is
                         unknowable; almost always a stdlib or framework
                         call on a type we do not map
      method_missing  -- receiver type IS one of ours but the method is not
                         on it; this is a genuine resolver failure and the
                         only number worth tracking as a quality metric
      not_ours        -- bare call matching nothing we declare
    """
    if "." in raw:
        receiver, _, method = raw.rpartition(".")
        receiver = receiver.split(".")[-1]

        type_name = decl.local_types.get(receiver)
        if type_name is None:
            type_name = receiver if receiver in types_by_name else None
        if type_name is None or type_name not in types_by_name:
            return None, "no_receiver"

        for tid in types_by_name.get(type_name, []):
            hit = methods.get((tid, method))
            if hit:
                return hit, "hit"
        return None, "method_missing"

    # bare call: own type first, then the file's module
    if own_type:
        hit = methods.get((own_type, raw))
        if hit:
            return hit, "hit"
    hit = toplevel.get((rf.module, raw))
    if hit:
        return hit, "hit"

    # a top-level function pulled in by import
    for imp in rf.imports:
        if imp.rsplit(".", 1)[-1] == raw:
            hit = toplevel.get((imp.rsplit(".", 1)[0], raw))
            if hit:
                return hit, "hit"
    return None, "not_ours"
