from render.build import build_html


def _tiny():
    return {
        "nodes": [{"id": "kt:A", "kind": "type", "name": "MiniRepository", "lang": "kotlin",
                   "file": "a.kt", "start_line": 1, "end_line": 2, "content_hash": "abc",
                   "parent": None, "layer": "domain", "kmp": "red", "annotations": [],
                   "modifiers": [], "signature": "A", "doc": "", "bridge_symbol": ""}],
        "edges": [],
        "stats": {"unresolved_calls": {"count": 0, "rate": 0, "top": []}, "violations": 0},
        "meta": {"generated": "now", "git_sha": "abc123", "tool_version": "0.1.0"},
    }


def test_output_loads_nothing_externally():
    """The offline guarantee is about fetching, not about text.

    Vendored libraries carry URLs in their licence headers; those are
    inert comments. What must not exist is any construct the browser
    would actually resolve over the network.
    """
    html = build_html(_tiny())
    assert "<script src=" not in html
    assert "<link " not in html
    assert "@import" not in html
    assert "url(http" not in html
    assert "fetch(" not in html.split("/*VENDOR*/")[0]


def test_our_own_sources_contain_no_urls():
    from render.build import HERE
    import re
    for name in ("template.html", "styles.css", "app.js"):
        assert not re.search(r"https?://", (HERE / name).read_text()), name


def test_graph_data_is_embedded_as_json():
    html = build_html(_tiny())
    assert 'id="codemap-data"' in html
    assert "MiniRepository" in html


def test_embedded_json_survives_a_closing_script_tag_in_source():
    data = _tiny()
    data["nodes"][0]["doc"] = "see </script> tag"
    html = build_html(data)
    assert "<\\/script> tag" in html


def test_vendor_libraries_are_inlined():
    html = build_html(_tiny())
    assert "cytoscape" in html.lower()
    assert len(html) > 500_000
