from graph.ids import file_id, function_id, module_id, type_id


def test_ids_are_namespaced_by_language():
    assert module_id("kotlin", "com.example.mini") == "kt-mod:com.example.mini"
    assert module_id("rust", "lib::storage") == "rs-mod:lib::storage"


def test_function_id_includes_qualifier_and_arity():
    assert function_id("kotlin", "com.example.mini", "MiniRepository", "cellCount", 0) == \
        "kt:com.example.mini.MiniRepository#cellCount/0"


def test_overloads_get_distinct_ids():
    assert function_id("kotlin", "p", "T", "f", 1) != function_id("kotlin", "p", "T", "f", 2)


def test_top_level_function_has_empty_qualifier_segment():
    assert function_id("kotlin", "com.example.mini", "", "MiniScreen", 1) == \
        "kt:com.example.mini#MiniScreen/1"


def test_type_id_shape():
    assert type_id("kotlin", "com.example.mini", "MiniRepository") == "kt:com.example.mini.MiniRepository"


def test_file_id_uses_repo_relative_path():
    assert file_id("android/app/src/A.kt") == "file:android/app/src/A.kt"


def test_rust_function_uses_scope_separator():
    assert function_id("rust", "lib::storage", "Store", "open", 1) == "rs:lib::storage::Store#open/1"
