"""Kotlin declaration extractor.

Deliberately dumb: it records what the AST says and nothing more. Call
targets are captured as raw text for graph/calls.py to resolve later, so
extraction stays independently testable and resolution can be improved
without touching parsing.
"""
from __future__ import annotations

from pathlib import Path

from tree_sitter import Node

from .model import RawDecl, RawFile
from .node_types import KT
from .ts_api import child_of_type, children_of_type, field, parse, text, walk


def _line(node: Node) -> int:
    return node.start_point[0] + 1


def _modifiers_and_annotations(node: Node, src: bytes) -> tuple[list[str], list[str]]:
    """Split a `modifiers` child into annotation names and plain modifiers."""
    mods: list[str] = []
    anns: list[str] = []
    m = child_of_type(node, KT.MODIFIERS)
    if m is None:
        return mods, anns
    for c in m.children:
        if c.type == KT.ANNOTATION:
            ut = next((n for n in walk(c) if n.type == KT.USER_TYPE), None)
            anns.append(text(ut, src).strip() if ut else text(c, src).lstrip("@").strip())
        else:
            mods.append(text(c, src).strip())
    return mods, anns


def _doc_above(src_text: str, start_byte_line: int) -> str:
    """Take a contiguous comment run immediately above a declaration.

    Blank lines and annotation lines between the comment and the
    declaration are tolerated, since `@Composable` routinely sits between
    a KDoc block and its function.
    """
    lines = src_text.splitlines()
    i = start_byte_line - 2  # 0-based index of the line above
    while i >= 0 and (not lines[i].strip() or lines[i].strip().startswith("@")):
        i -= 1
    if i < 0:
        return ""

    collected: list[str] = []
    stripped = lines[i].strip()
    if stripped.endswith("*/"):
        while i >= 0:
            collected.append(lines[i])
            if lines[i].strip().startswith("/*"):
                break
            i -= 1
        collected.reverse()
    elif stripped.startswith("//"):
        while i >= 0 and lines[i].strip().startswith("//"):
            collected.append(lines[i])
            i -= 1
        collected.reverse()
    else:
        return ""

    out = []
    for ln in collected:
        s = ln.strip()
        for prefix in ("/**", "/*", "*/", "*", "///", "//"):
            if s.startswith(prefix):
                s = s[len(prefix) :]
                break
        s = s.removesuffix("*/").strip()
        if s:
            out.append(s)
    return " ".join(out).strip()


def _type_names(node: Node, src: bytes) -> list[str]:
    return [text(u, src).split("<")[0].strip() for u in walk(node) if u.type == KT.USER_TYPE]


def _enclosing_type_name(node: Node, src: bytes) -> str:
    """Owner name for a declaration.

    An anonymous `object : X { }` produces no declaration node at all --
    it is inline in the property declaration -- so members would get an
    empty qualifier and every same-name, same-arity method in a package
    would collapse to one graph node. Migrations.kt alone has 14
    `migrate(db)` declarations that way. Fall back to the enclosing
    property name, which is both unique and the name a reader would use.
    """
    p = node.parent
    while p is not None:
        if p.type in KT.TYPE_DECLS:
            n = field(p, "name")
            return text(n, src) if n else ""
        if p.type == KT.PROPERTY:
            var = child_of_type(p, KT.VARIABLE)
            ident = child_of_type(var, KT.IDENT) if var is not None else None
            if ident is not None:
                return text(ident, src)
        p = p.parent
    return ""


def _collect_calls(fn_node: Node, src: bytes) -> list[str]:
    """Raw callee text for every call inside a function body."""
    calls: list[str] = []
    for n in walk(fn_node):
        if n.type != KT.CALL:
            continue
        target = n.children[0] if n.children else None
        if target is None:
            continue
        if target.type == KT.NAVIGATION:
            calls.append(text(target, src).strip())
        elif target.type == KT.IDENT:
            calls.append(text(target, src).strip())
    return calls


def _local_types(fn_node: Node, src: bytes) -> dict[str, str]:
    """Locals declared with an explicit type or an obvious constructor call."""
    out: dict[str, str] = {}
    for n in walk(fn_node):
        if n.type != KT.PROPERTY:
            continue
        var = child_of_type(n, KT.VARIABLE)
        if var is None:
            continue
        name_node = child_of_type(var, KT.IDENT)
        if name_node is None:
            continue
        name = text(name_node, src)
        declared = child_of_type(var, KT.USER_TYPE)
        if declared is not None:
            out[name] = text(declared, src).split("<")[0].strip()
            continue
        call = next((c for c in n.children if c.type == KT.CALL), None)
        if call is not None and call.children:
            head = call.children[0]
            if head.type == KT.IDENT:
                out[name] = text(head, src)
    return out


def _class_member_types(type_node: Node, src: bytes) -> dict[str, str]:
    """Constructor params and properties, so `repo.foo()` can resolve."""
    out: dict[str, str] = {}
    ctor = next((n for n in walk(type_node) if n.type == KT.PRIMARY_CONSTRUCTOR), None)
    if ctor is not None:
        for cp in (n for n in walk(ctor) if n.type == KT.CLASS_PARAM):
            ident = child_of_type(cp, KT.IDENT)
            ut = child_of_type(cp, KT.USER_TYPE)
            if ident is not None and ut is not None:
                out[text(ident, src)] = text(ut, src).split("<")[0].strip()

    body = child_of_type(type_node, KT.CLASS_BODY)
    if body is not None:
        for prop in children_of_type(body, KT.PROPERTY):
            var = child_of_type(prop, KT.VARIABLE)
            if var is None:
                continue
            ident = child_of_type(var, KT.IDENT)
            ut = child_of_type(var, KT.USER_TYPE)
            if ident is not None and ut is not None:
                out[text(ident, src)] = text(ut, src).split("<")[0].strip()
    return out


def extract_kotlin(abs_path: Path, rel_path: str) -> RawFile:
    src = abs_path.read_bytes()
    src_text = src.decode("utf8", errors="replace")
    res = parse(src, "kotlin")

    rf = RawFile(path=rel_path, lang="kotlin", module="", parse_error=res.had_error)

    for node in walk(res.root):
        if node.type == KT.PACKAGE and not rf.module:
            q = next((n for n in walk(node) if n.type == KT.QUALIFIED_IDENT), None)
            rf.module = text(q, src).strip() if q else ""

        elif node.type == KT.IMPORT:
            q = next((n for n in walk(node) if n.type == KT.QUALIFIED_IDENT), None)
            if q is None:
                continue
            fqn = text(q, src).strip()
            rf.imports.append(fqn)
            trailing = [n for n in node.children if n.type == KT.IDENT]
            if trailing:
                rf.aliases[text(trailing[-1], src)] = fqn

    # Types, then functions, so member lookups have their owners available.
    member_types_by_owner: dict[str, dict[str, str]] = {}

    for node in walk(res.root):
        if node.type not in KT.TYPE_DECLS:
            continue
        name_node = field(node, "name")
        if name_node is None:
            continue
        name = text(name_node, src)
        mods, anns = _modifiers_and_annotations(node, src)
        ctor = next((n for n in walk(node) if n.type == KT.PRIMARY_CONSTRUCTOR), None)
        if ctor is not None:
            cmods, canns = _modifiers_and_annotations(ctor, src)
            anns.extend(canns)
        supertypes = [
            t
            for dl in children_of_type(node, KT.DELEGATION_LIST)
            for d in children_of_type(dl, KT.DELEGATION)
            for t in _type_names(d, src)
        ]
        param_types: list[str] = []
        if ctor is not None:
            for cp in (n for n in walk(ctor) if n.type == KT.CLASS_PARAM):
                ut = child_of_type(cp, KT.USER_TYPE)
                if ut is not None:
                    param_types.append(text(ut, src).split("<")[0].strip())

        member_types_by_owner[name] = _class_member_types(node, src)

        rf.decls.append(
            RawDecl(
                kind="type",
                name=name,
                start_line=_line(node),
                end_line=node.end_point[0] + 1,
                source=text(node, src),
                qualifier=_enclosing_type_name(node, src),
                annotations=anns,
                modifiers=mods,
                param_types=param_types,
                supertypes=supertypes,
                doc=_doc_above(src_text, _line(node)),
            )
        )

    for node in walk(res.root):
        if node.type != KT.FUNCTION:
            continue
        name_node = field(node, "name")
        if name_node is None:
            continue
        name = text(name_node, src)
        mods, anns = _modifiers_and_annotations(node, src)
        owner = _enclosing_type_name(node, src)

        params = child_of_type(node, KT.FUNCTION_PARAMS)
        param_types: list[str] = []
        if params is not None:
            for p in children_of_type(params, KT.PARAM):
                ut = child_of_type(p, KT.USER_TYPE)
                if ut is not None:
                    param_types.append(text(ut, src).split("<")[0].strip())

        scope = dict(member_types_by_owner.get(owner, {}))
        scope.update(_local_types(node, src))
        if params is not None:
            for p in children_of_type(params, KT.PARAM):
                ident = child_of_type(p, KT.IDENT)
                ut = child_of_type(p, KT.USER_TYPE)
                if ident is not None and ut is not None:
                    scope[text(ident, src)] = text(ut, src).split("<")[0].strip()

        rf.decls.append(
            RawDecl(
                kind="function",
                name=name,
                start_line=_line(node),
                end_line=node.end_point[0] + 1,
                source=text(node, src),
                qualifier=owner,
                annotations=anns,
                modifiers=mods,
                param_types=param_types,
                doc=_doc_above(src_text, _line(node)),
                calls=_collect_calls(node, src),
                local_types=scope,
            )
        )

    return rf
