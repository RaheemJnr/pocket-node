from graph.bridge import pair_uniffi, uniffi_swift_name


def test_snake_case_becomes_camel_case():
    assert uniffi_swift_name("get_cell_count") == "getCellCount"
    assert uniffi_swift_name("start_node") == "startNode"


def test_already_camel_is_left_alone():
    assert uniffi_swift_name("getCells") == "getCells"


def test_single_word_is_unchanged():
    assert uniffi_swift_name("boot") == "boot"


def test_leading_underscore_is_not_capitalised_away():
    assert uniffi_swift_name("_internal_fn") == "_internalFn"


def test_pairs_exported_rust_to_swift_callers_and_reports_orphans():
    rust = {"get_cell_count": "rs:api#get_cell_count/1",
            "unused_export": "rs:api#unused_export/0"}
    swift_syms = {"getCellCount": ["sw:W#cellCount/0"]}
    r = pair_uniffi(rust, swift_syms)
    assert r.paired == [("sw:W#cellCount/0", "rs:api#get_cell_count/1")]
    assert r.rust_orphans == ["unused_export"]
    assert r.swift_orphans == []


def test_swift_call_to_unknown_uniffi_name_is_a_swift_orphan():
    r = pair_uniffi({}, {"missingInRust": ["sw:W#f/0"]})
    assert r.swift_orphans == ["missingInRust"]
