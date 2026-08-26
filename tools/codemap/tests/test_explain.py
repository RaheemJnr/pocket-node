def test_direct_marker_is_reported_with_no_chain(mini_full):
    e = __import__("graph.explain", fromlist=["explain_kmp"]).explain_kmp(
        mini_full, mini_full.find_one(kind="function", name="nativeGetCells").id)
    assert e["kmp"] == "red"
    assert e["blocked_by_self"] is True
    assert "external" in e["summary"]


def test_propagated_node_reports_the_chain_to_its_root(mini_full):
    from graph.explain import explain_kmp
    e = explain_kmp(mini_full, mini_full.find_one(kind="function", name="refresh").id)
    assert e["kmp"] == "amber"
    assert e["blocked_by_self"] is False
    assert len(e["chain"]) >= 2
    assert "root cause" in e["summary"]


def test_green_node_says_it_can_move(mini_full):
    from graph.explain import explain_kmp
    e = explain_kmp(mini_full, mini_full.find_one(kind="function", name="pureHelper").id)
    assert e["kmp"] == "green"
    assert "commonMain" in e["summary"]


def test_chain_terminates_on_a_cycle(mini_full):
    from graph.explain import explain_kmp
    n = mini_full.find_one(kind="function", name="cellCount")
    mini_full.nodes[n.id].kmp_via = n.id           # self-reference
    assert len(explain_kmp(mini_full, n.id)["chain"]) == 1
