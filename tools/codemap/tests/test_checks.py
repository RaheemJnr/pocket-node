from pathlib import Path

from graph.checks import (check_bridges_paired, check_no_layer_violations,
                          check_parse_errors, check_unresolved_rate, run_all)
from graph.graph import Graph, Node
from graph.layers import load_rules

RULES = Path(__file__).resolve().parent.parent / "rules"
load_rules(RULES / "layers.yaml")


def _g():
    return Graph()


def test_bridge_orphans_are_reported_on_both_sides():
    g = _g()
    g.stats["bridge"] = {"kotlin_orphans": ["Java_a_B_missing"], "rust_orphans": ["Java_a_B_extra"]}
    g.stats["uniffi"] = {"swift_orphans": ["ghostFn"]}
    msgs = check_bridges_paired(g)
    assert len(msgs) == 3
    assert any("no Rust implementation" in m for m in msgs)
    assert any("no Kotlin declaration" in m for m in msgs)
    assert any("no Rust export" in m for m in msgs)


def test_fully_paired_bridge_passes():
    g = _g()
    g.stats["bridge"] = {"kotlin_orphans": [], "rust_orphans": []}
    assert check_bridges_paired(g) == []


def test_backward_edge_is_reported():
    g = _g()
    g.add_node(Node(id="a", kind="function", name="f", lang="kotlin", file="a.kt", layer="data"))
    g.add_node(Node(id="b", kind="function", name="g", lang="kotlin", file="b.kt", layer="ui"))
    g.add_edge("a", "b", "calls")
    assert len(check_no_layer_violations(g)) == 1


def test_leaf_layer_dependency_is_not_reported():
    g = _g()
    g.add_node(Node(id="a", kind="function", name="f", lang="kotlin", file="a.kt", layer="viewmodel"))
    g.add_node(Node(id="b", kind="function", name="g", lang="kotlin", file="b.kt", layer="util"))
    g.add_edge("a", "b", "calls")
    assert check_no_layer_violations(g) == []


def test_allowlisted_parse_error_passes_and_others_fail():
    g = _g()
    g.stats["parse_errors"] = ["known.kt", "surprise.kt"]
    msgs = check_parse_errors(g, {"known.kt"})
    assert len(msgs) == 1 and "surprise.kt" in msgs[0]


def test_unresolved_rate_ceiling():
    g = _g()
    g.stats["unresolved_calls"] = {"rate": 0.30}
    assert check_unresolved_rate(g, 0.20)
    assert check_unresolved_rate(g, 0.40) == []


def test_run_all_returns_a_bucket_per_check():
    g = _g()
    g.stats["unresolved_calls"] = {"rate": 0.0}
    r = run_all(g, {})
    assert set(r) == {"bridges_paired", "no_layer_violations", "parse_errors", "unresolved_rate"}
