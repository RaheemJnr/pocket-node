def test_injects_edge_from_constructor_param_to_type(mini_full):
    vm = mini_full.find_one(kind="type", name="MiniViewModel")
    repo = mini_full.find_one(kind="type", name="MiniRepository")
    assert mini_full.has_edge(vm.id, repo.id, "injects")


def test_composable_functions_are_flagged(mini_full):
    assert "Composable" in mini_full.find_one(kind="function", name="MiniScreen").annotations
