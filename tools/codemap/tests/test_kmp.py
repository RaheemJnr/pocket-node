def test_class_holding_context_is_red(mini_full):
    assert mini_full.find_one(kind="type", name="MiniRepository").kmp == "red"


def test_pure_method_inside_red_class_stays_green(mini_full):
    # The assertion that justifies function-level extraction: if this ever
    # inherits its class's colour, the tool stops answering the question it
    # was built for.
    assert mini_full.find_one(kind="function", name="pureHelper").kmp == "green"


def test_external_fun_is_red_by_marker_regardless_of_imports(mini_full):
    assert mini_full.find_one(kind="function", name="nativeGetCells").kmp == "red"


def test_method_calling_the_bridge_is_amber_not_red(mini_full):
    # cellCount() is not itself Android-bound; it calls something that is.
    assert mini_full.find_one(kind="function", name="cellCount").kmp == "amber"


def test_propagation_demotes_a_clean_caller_of_dirty_code(mini_full):
    assert mini_full.find_one(kind="function", name="refresh").kmp == "amber"


def test_composable_is_red(mini_full):
    assert mini_full.find_one(kind="function", name="MiniScreen").kmp == "red"


def test_rust_nodes_are_not_classified(mini_full):
    for n in mini_full.nodes.values():
        if n.lang == "rust":
            assert n.kmp == "unknown"


def test_aliased_import_is_classified_by_its_alias(tmp_path):
    """`import a.b.C as D` then using `D` must classify by a.b.C.

    Matching only the original final segment misses the identifier that
    actually appears: data/crypto/Blake2b.kt aliases the amber CKB SDK to
    CkbBlake2b, and hash() was classified green -- an unsafe commonMain
    recommendation.
    """
    from pathlib import Path

    from extract.kotlin import extract_kotlin
    from graph import kmp as kmp_pass
    from graph.build import build_graph

    p = tmp_path / "Hasher.kt"
    p.write_text(
        "package com.example.mini\n\n"
        "import org.nervos.ckb.crypto.Blake2b as CkbBlake2b\n\n"
        "class Hasher {\n"
        "    fun hash(input: ByteArray): ByteArray {\n"
        "        return CkbBlake2b.digest(input)\n"
        "    }\n"
        "}\n"
    )
    rf = extract_kotlin(p, "mini/kotlin/Hasher.kt")
    g = build_graph([rf])
    rules = Path(__file__).resolve().parent.parent / "rules" / "kmp.yaml"
    kmp_pass.apply(g, [rf], rules)
    assert g.find_one(kind="function", name="hash").kmp == "amber"
