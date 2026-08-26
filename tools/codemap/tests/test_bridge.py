from graph.bridge import mangle_jni_symbol, pair_bridge


def test_mangles_package_class_and_method():
    assert mangle_jni_symbol("com.nervosnetwork.ckblightclient", "LightClientNative", "nativeGetCells") == \
        "Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetCells"


def test_escapes_underscores_per_jni_spec():
    assert mangle_jni_symbol("com.a", "My_Class", "my_method") == "Java_com_a_My_1Class_my_1method"


def test_pairs_matching_symbols_and_reports_orphans_on_both_sides():
    kotlin = {"Java_com_example_mini_LightClientNative_nativeGetCells": "kt:a#nativeGetCells/1",
              "Java_com_example_mini_LightClientNative_nativeMissingInRust": "kt:a#nativeMissingInRust/0"}
    rust = {"Java_com_example_mini_LightClientNative_nativeGetCells": "rs:b",
            "Java_com_example_mini_LightClientNative_nativeOrphanFn": "rs:orphan"}
    r = pair_bridge(kotlin, rust)
    assert len(r.paired) == 1
    assert r.kotlin_orphans == ["Java_com_example_mini_LightClientNative_nativeMissingInRust"]
    assert r.rust_orphans == ["Java_com_example_mini_LightClientNative_nativeOrphanFn"]


def test_fixture_bridge_pairs_across_languages(mini_full):
    kt = mini_full.find_one(kind="function", name="nativeGetCells")
    rs = mini_full.find_one(kind="function", name="Java_com_example_mini_LightClientNative_nativeGetCells")
    assert mini_full.has_edge(kt.id, rs.id, "bridges")
    stats = mini_full.to_dict()["stats"]["bridge"]
    assert stats["paired"] == 1
    assert len(stats["kotlin_orphans"]) == 1
    assert len(stats["rust_orphans"]) == 1
