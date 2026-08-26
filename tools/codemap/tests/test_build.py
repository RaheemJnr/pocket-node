def test_creates_module_file_type_function_nodes(mini_graph):
    kinds = {n.kind for n in mini_graph.nodes.values()}
    assert {"module", "file", "type", "function"} <= kinds


def test_containment_chain_is_complete(mini_graph):
    fn = mini_graph.find_one(kind="function", name="cellCount")
    typ = mini_graph.parent_of(fn.id)
    assert typ.name == "MiniRepository"
    f = mini_graph.parent_of(typ.id)
    assert f.kind == "file"
    mod = mini_graph.parent_of(f.id)
    assert mod.name == "com.example.mini"


def test_node_carries_hash_and_location(mini_graph):
    fn = mini_graph.find_one(kind="function", name="cellCount")
    assert len(fn.content_hash) == 12
    assert fn.file.endswith("MiniRepository.kt")
    assert fn.start_line > 0


def test_serialization_shape(mini_graph):
    data = mini_graph.to_dict()
    assert set(data.keys()) == {"nodes", "edges", "stats", "meta"}
    assert all("id" in n for n in data["nodes"])
    assert "nodes_by_kind" in data["stats"]


def test_duplicate_edges_are_deduped(mini_graph):
    before = len(mini_graph.edges)
    e = mini_graph.edges[0]
    mini_graph.add_edge(e.src, e.dst, e.kind)
    assert len(mini_graph.edges) == before
