from graph.rollup import apply, describe_file, describe_module


def test_file_inherits_its_namesake_type_description(mini_graph):
    f = mini_graph.find_one(kind="file", name="MiniRepository.kt")
    t = mini_graph.find_one(kind="type", name="MiniRepository")
    s = {t.id: {"text": "Does repository things.", "hash": t.content_hash}}
    assert describe_file(mini_graph, f.id, s) == "Does repository things."


def test_file_with_extra_types_says_so(mini_graph):
    f = mini_graph.find_one(kind="file", name="MiniRepository.kt")
    t = mini_graph.find_one(kind="type", name="MiniRepository")
    mini_graph.add_node(type(t)(id="kt:com.example.mini.Other", kind="type", name="Other",
                                lang="kotlin", file=f.file, parent=f.id))
    s = {t.id: {"text": "Does repository things.", "hash": t.content_hash}}
    assert "1 other type" in describe_file(mini_graph, f.id, s)


def test_file_without_a_namesake_lists_its_types(mini_graph):
    f = mini_graph.find_one(kind="file", name="LightClientNative.kt")
    out = describe_file(mini_graph, f.id, {})
    assert "LightClientNative" in out


def test_module_summarises_its_files_and_types(mini_graph):
    m = mini_graph.find_one(kind="module", name="com.example.mini")
    out = describe_module(mini_graph, m.id, {})
    assert "file" in out and "declaring" in out


def test_apply_fills_containers_and_leaves_existing_alone(mini_graph):
    s = {}
    n = apply(mini_graph, s)
    assert n > 0
    assert all(v.get("derived") for v in s.values())
    before = dict(s)
    assert apply(mini_graph, s) == 0        # idempotent
    assert s == before


def test_derived_entries_are_never_stale(mini_graph):
    s = {}
    apply(mini_graph, s)
    for nid, rec in s.items():
        assert rec["hash"] == mini_graph.nodes[nid].content_hash
