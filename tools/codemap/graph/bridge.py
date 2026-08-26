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


# ---- UniFFI (iOS) -------------------------------------------------------

def uniffi_swift_name(rust_name: str) -> str:
    """UniFFI lowerCamelCases Rust snake_case for the Swift bindings."""
    lead = len(rust_name) - len(rust_name.lstrip("_"))
    prefix, body = rust_name[:lead], rust_name[lead:]
    if "_" not in body:
        return rust_name
    head, *rest = body.split("_")
    return prefix + head + "".join(p[:1].upper() + p[1:] for p in rest if p)


@dataclass
class UniffiPairing:
    paired: list[tuple[str, str]] = field(default_factory=list)
    rust_orphans: list[str] = field(default_factory=list)
    swift_orphans: list[str] = field(default_factory=list)


def pair_uniffi(rust_exports: dict[str, str],
                swift_symbols: dict[str, list[str]]) -> UniffiPairing:
    """Match `#[uniffi::export]` Rust functions to their Swift call sites.

    The generated Swift bindings are build artifacts and are not in the
    repository, so the edge runs from the Swift function that CALLS the
    binding straight to the Rust function behind it. That keeps the graph
    connected across the boundary without depending on generated code.
    """
    result = UniffiPairing()
    swift_seen: set[str] = set()

    for rust_name, rs_id in sorted(rust_exports.items()):
        swift_name = uniffi_swift_name(rust_name)
        callers = swift_symbols.get(swift_name)
        if callers:
            swift_seen.add(swift_name)
            for sw_id in callers:
                result.paired.append((sw_id, rs_id))
        else:
            result.rust_orphans.append(rust_name)

    for swift_name in sorted(swift_symbols):
        if swift_name not in swift_seen:
            result.swift_orphans.append(swift_name)
    return result


UNIFFI_MARKERS = ("uniffi::export", "uniffi::constructor", "uniffi::method")


def apply_uniffi(g: Graph, files: list[RawFile]) -> UniffiPairing:
    rust_exports: dict[str, str] = {}
    swift_symbols: dict[str, list[str]] = {}

    for rf in files:
        for d in rf.decls:
            if d.kind != "function":
                continue
            nid = function_id(rf.lang, rf.module, d.qualifier, d.name, len(d.param_types))
            if rf.lang == "rust":
                if any(m in a for a in d.annotations for m in UNIFFI_MARKERS):
                    rust_exports[d.name] = nid
                    if nid in g.nodes:
                        g.nodes[nid].bridge_symbol = f"uniffi:{d.name}"
            elif rf.lang == "swift":
                for raw in d.calls:
                    if "." in raw:          # a bare call is the binding; a.b() is not
                        continue
                    swift_symbols.setdefault(raw, []).append(nid)

    pairing = pair_uniffi(rust_exports, swift_symbols)
    for sw_id, rs_id in pairing.paired:
        g.add_edge(sw_id, rs_id, "bridges")
        if sw_id in g.nodes and not g.nodes[sw_id].bridge_symbol:
            g.nodes[sw_id].bridge_symbol = g.nodes[rs_id].bridge_symbol

    g.stats["uniffi"] = {
        "paired": len(pairing.paired),
        "rust_orphans": pairing.rust_orphans,
        "swift_orphans": pairing.swift_orphans,
    }
    return pairing
