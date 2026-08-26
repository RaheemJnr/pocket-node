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
