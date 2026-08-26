def test_resolves_call_through_injected_field(mini_full):
    src = mini_full.find_one(kind="function", name="refresh")
    dst = mini_full.find_one(kind="function", name="cellCount")
    assert mini_full.has_edge(src.id, dst.id, "calls")


def test_resolves_call_to_object_member(mini_full):
    src = mini_full.find_one(kind="function", name="cellCount")
    dst = mini_full.find_one(kind="function", name="nativeGetCells")
    assert mini_full.has_edge(src.id, dst.id, "calls")


def test_unresolved_calls_are_recorded_not_dropped(mini_full):
    stats = mini_full.to_dict()["stats"]["unresolved_calls"]
    assert isinstance(stats["count"], int)
    assert isinstance(stats["top"], list)
    assert 0.0 <= stats["rate"] <= 1.0


def test_call_edges_are_marked_heuristic(mini_full):
    src = mini_full.find_one(kind="function", name="refresh")
    dst = mini_full.find_one(kind="function", name="cellCount")
    assert mini_full.get_edge(src.id, dst.id, "calls").confidence == "heuristic"
