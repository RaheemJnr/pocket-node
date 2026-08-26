from pathlib import Path

from extract.kotlin import extract_kotlin

FIX = Path(__file__).parent / "fixtures" / "mini" / "kotlin"


def test_extracts_package_and_imports():
    rf = extract_kotlin(FIX / "MiniRepository.kt", "mini/kotlin/MiniRepository.kt")
    assert rf.module == "com.example.mini"
    assert "android.content.Context" in rf.imports
    assert rf.parse_error is False


def test_extracts_class_and_its_methods():
    rf = extract_kotlin(FIX / "MiniRepository.kt", "x.kt")
    names = {d.name: d for d in rf.decls}
    assert names["MiniRepository"].kind == "type"
    assert names["cellCount"].kind == "function"
    assert names["cellCount"].qualifier == "MiniRepository"
    assert names["pureHelper"].qualifier == "MiniRepository"


def test_captures_doc_comment_when_present():
    rf = extract_kotlin(FIX / "MiniRepository.kt", "x.kt")
    doc = {d.name: d.doc for d in rf.decls}
    assert "Fetches the live cell count" in doc["cellCount"]
    assert doc["pureHelper"] == ""


def test_captures_annotations_and_external_modifier():
    rf = extract_kotlin(FIX / "LightClientNative.kt", "x.kt")
    d = {x.name: x for x in rf.decls}
    assert "external" in d["nativeGetCells"].modifiers

    rf2 = extract_kotlin(FIX / "MiniScreen.kt", "x.kt")
    d2 = {x.name: x for x in rf2.decls}
    assert "Composable" in d2["MiniScreen"].annotations


def test_captures_constructor_param_types_for_injection():
    rf = extract_kotlin(FIX / "MiniViewModel.kt", "x.kt")
    vm = {d.name: d for d in rf.decls}["MiniViewModel"]
    assert "MiniRepository" in vm.param_types
    assert "ViewModel" in vm.supertypes
    assert "Inject" in vm.annotations


def test_captures_raw_call_text_for_later_resolution():
    rf = extract_kotlin(FIX / "MiniViewModel.kt", "x.kt")
    refresh = {d.name: d for d in rf.decls}["refresh"]
    assert any("cellCount" in c for c in refresh.calls)


def test_records_receiver_types_for_call_resolution():
    rf = extract_kotlin(FIX / "MiniViewModel.kt", "x.kt")
    refresh = {d.name: d for d in rf.decls}["refresh"]
    assert refresh.local_types.get("repository") == "MiniRepository"


def test_object_declaration_is_a_type():
    rf = extract_kotlin(FIX / "LightClientNative.kt", "x.kt")
    d = {x.name: x for x in rf.decls}
    assert d["LightClientNative"].kind == "type"
