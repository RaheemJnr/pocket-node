from pathlib import Path

from extract.rust import extract_rust

FIX = Path(__file__).parent / "fixtures" / "mini" / "rust"


def test_extracts_functions_and_types():
    rf = extract_rust(FIX / "storage.rs", "mini/rust/storage.rs")
    names = {d.name: d for d in rf.decls}
    assert names["read_cell_count"].kind == "function"
    assert names["Store"].kind == "type"
    assert names["open"].qualifier == "Store"


def test_captures_rustdoc():
    rf = extract_rust(FIX / "storage.rs", "x.rs")
    doc = {d.name: d.doc for d in rf.decls}
    assert "Reads a cell count" in doc["read_cell_count"]


def test_captures_no_mangle_extern_functions():
    rf = extract_rust(FIX / "bridge.rs", "x.rs")
    d = {x.name: x for x in rf.decls}
    fn = d["Java_com_example_mini_LightClientNative_nativeGetCells"]
    assert "no_mangle" in fn.annotations
    assert "extern" in fn.modifiers


def test_module_path_derives_from_file_path():
    rf = extract_rust(FIX / "storage.rs", "external/x/src/jni_bridge/storage.rs")
    assert rf.module.endswith("jni_bridge::storage")


def test_captures_calls():
    rf = extract_rust(FIX / "bridge.rs", "x.rs")
    fn = {d.name: d for d in rf.decls}["Java_com_example_mini_LightClientNative_nativeGetCells"]
    assert any("read_cell_count" in c for c in fn.calls)


def test_flattens_use_declarations():
    rf = extract_rust(FIX / "bridge.rs", "x.rs")
    assert any("read_cell_count" in i for i in rf.imports)
