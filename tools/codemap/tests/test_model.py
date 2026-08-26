from extract.model import RawDecl, RawFile


def test_rawdecl_content_hash_is_stable_and_whitespace_insensitive():
    a = RawDecl(kind="function", name="f", start_line=1, end_line=2,
                source="fun f() {\n  return 1\n}")
    b = RawDecl(kind="function", name="f", start_line=90, end_line=91,
                source="fun f() {\n  return 1\n}   ")
    assert a.content_hash == b.content_hash
    assert len(a.content_hash) == 12


def test_rawdecl_content_hash_changes_with_body():
    a = RawDecl(kind="function", name="f", start_line=1, end_line=1, source="fun f() = 1")
    b = RawDecl(kind="function", name="f", start_line=1, end_line=1, source="fun f() = 2")
    assert a.content_hash != b.content_hash


def test_rawfile_defaults_are_independent():
    x, y = RawFile(path="a", lang="kotlin", module="m"), RawFile(path="b", lang="kotlin", module="m")
    x.imports.append("z")
    assert y.imports == []
