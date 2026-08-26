"""JNI bridge stitching.

The only place automation can join the Rust graph to the Kotlin graph.
Matching is by mangled symbol rather than by bare method name, so a
rename on one side shows up as an orphan pair instead of silently
vanishing.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from extract.model import RawFile

from .graph import Graph
from .ids import function_id


def mangle_jni_symbol(package: str, class_name: str, method: str) -> str:
    """Build the C symbol the JVM looks for, per the JNI mangling rules."""
    def esc(s: str) -> str:
        out = []
        for ch in s:
            if ch == "_":
                out.append("_1")
            elif ch == ";":
                out.append("_2")
            elif ch == "[":
                out.append("_3")
            elif ch == ".":
                out.append("_")
            elif ch.isascii() and ch.isalnum():
                out.append(ch)
            else:
                out.append("_0%04x" % ord(ch))
        return "".join(out)

    return f"Java_{esc(package)}_{esc(class_name)}_{esc(method)}"


@dataclass
class BridgePairing:
    paired: list[tuple[str, str]] = field(default_factory=list)
    kotlin_orphans: list[str] = field(default_factory=list)
    rust_orphans: list[str] = field(default_factory=list)


def pair_bridge(kotlin_syms: dict[str, str], rust_syms: dict[str, str]) -> BridgePairing:
    result = BridgePairing()
    for sym, kt_id in sorted(kotlin_syms.items()):
        if sym in rust_syms:
            result.paired.append((kt_id, rust_syms[sym]))
        else:
            result.kotlin_orphans.append(sym)
    for sym in sorted(rust_syms):
        if sym not in kotlin_syms:
            result.rust_orphans.append(sym)
    return result


def apply(g: Graph, files: list[RawFile]) -> BridgePairing:
    kotlin_syms: dict[str, str] = {}
    rust_syms: dict[str, str] = {}

    for rf in files:
        for d in rf.decls:
            if d.kind != "function":
                continue
            nid = function_id(rf.lang, rf.module, d.qualifier, d.name, len(d.param_types))
            if rf.lang == "kotlin" and "external" in d.modifiers:
                sym = mangle_jni_symbol(rf.module, d.qualifier, d.name)
                kotlin_syms[sym] = nid
                if nid in g.nodes:
                    g.nodes[nid].bridge_symbol = sym
            elif rf.lang == "rust" and d.name.startswith("Java_"):
                rust_syms[d.name] = nid
                if nid in g.nodes:
                    g.nodes[nid].bridge_symbol = d.name

    pairing = pair_bridge(kotlin_syms, rust_syms)
    for kt_id, rs_id in pairing.paired:
        g.add_edge(kt_id, rs_id, "bridges")

    g.stats["bridge"] = {
        "paired": len(pairing.paired),
        "kotlin_orphans": pairing.kotlin_orphans,
        "rust_orphans": pairing.rust_orphans,
    }
    return pairing
