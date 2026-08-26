from pathlib import Path

from extract.swift import extract_swift

FIX = Path(__file__).parent / "fixtures" / "mini" / "swift"


def test_extracts_struct_and_methods():
    rf = extract_swift(FIX / "WalletService.swift", "mini/swift/WalletService.swift")
    assert rf.parse_error is False
    d = {x.name: x for x in rf.decls}
    assert d["WalletService"].kind == "type"
    assert d["cellCount"].kind == "function"
    assert d["cellCount"].qualifier == "WalletService"


def test_captures_doc_comment():
    rf = extract_swift(FIX / "WalletService.swift", "x.swift")
    doc = {x.name: x.doc for x in rf.decls}
    assert "Reads the live cell count" in doc["cellCount"]


def test_captures_imports():
    rf = extract_swift(FIX / "WalletService.swift", "x.swift")
    assert "Foundation" in rf.imports


def test_captures_calls_including_bare_uniffi_names():
    rf = extract_swift(FIX / "WalletService.swift", "x.swift")
    d = {x.name: x for x in rf.decls}
    assert any("getCellCount" in c for c in d["cellCount"].calls)
    assert any("startNode" in c for c in d["boot"].calls)


def test_records_member_types_for_resolution():
    rf = extract_swift(FIX / "HomeView.swift", "x.swift")
    d = {x.name: x for x in rf.decls}
    assert d["refresh"].local_types.get("service") == "WalletService"


def test_module_is_the_directory():
    rf = extract_swift(FIX / "WalletService.swift", "ios/Sources/Core/WalletService.swift")
    assert "Core" in rf.module
