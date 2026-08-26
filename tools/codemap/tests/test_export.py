import re

from render.export import to_mermaid


def _data():
    def n(i, name, layer, kind="type"):
        return {"id": i, "name": name, "layer": layer, "kind": kind}
    return {
        "nodes": [n("a", "Repo", "domain"), n("b", "Dao", "data"),
                  n("c", "Screen", "ui"), n("d", "Native", "jni")],
        "edges": [{"src": "a", "dst": "b", "kind": "calls"},
                  {"src": "c", "dst": "a", "kind": "calls"},
                  {"src": "a", "dst": "d", "kind": "bridges"},
                  {"src": "a", "dst": "b", "kind": "contains"}],
        "stats": {"layer_meta": {"data": {"order": 2, "label": "Data"},
                                 "domain": {"order": 3, "label": "Repository"},
                                 "ui": {"order": 5, "label": "UI"},
                                 "jni": {"order": 1, "label": "JNI"}}},
    }


def test_emits_a_flowchart_with_layer_subgraphs():
    m = to_mermaid(_data())
    assert m.startswith("flowchart LR")
    assert 'subgraph data["Data"]' in m
    assert 'subgraph ui["UI"]' in m


def test_containment_edges_are_not_drawn():
    assert to_mermaid(_data()).count("-->") + to_mermaid(_data()).count("==>") == 3


def test_bridge_edges_use_a_thick_arrow_and_label():
    assert "==>|bridges|" in to_mermaid(_data())


def test_focus_limits_to_the_neighbourhood():
    m = to_mermaid(_data(), focus="c", depth=1)
    assert "Screen" in m and "Repo" in m
    assert "Native" not in m          # two hops from Screen


def test_focus_node_is_emphasised():
    assert "style n" in to_mermaid(_data(), focus="a")


def test_node_cap_is_honoured():
    m = to_mermaid(_data(), max_nodes=2)
    assert m.count('["') <= 2 + 4     # nodes plus subgraph labels


def test_ids_are_mermaid_safe():
    d = _data()
    d["nodes"][0]["id"] = "kt:com.x.Y#f/0"
    d["edges"] = [{"src": "kt:com.x.Y#f/0", "dst": "b", "kind": "calls"}]
    m = to_mermaid(d)
    assert ":" not in m.split("flowchart LR")[1].split("\n")[1]


def test_long_ids_that_share_a_prefix_do_not_collide():
    # These share their first 60 characters; truncation alone merged them.
    a = "kt:com.rjnr.pocketnode.data.gateway.GatewayRepository#refreshBalance/0"
    b = "kt:com.rjnr.pocketnode.data.gateway.GatewayRepository#refreshBalanceForWallet/2"
    d = {"nodes": [{"id": a, "name": "refreshBalance", "layer": "domain", "kind": "function"},
                   {"id": b, "name": "refreshBalanceForWallet", "layer": "domain", "kind": "function"}],
         "edges": [{"src": a, "dst": b, "kind": "calls"}],
         "stats": {"layer_meta": {"domain": {"order": 3, "label": "Repository"}}}}
    m = to_mermaid(d, kind="function")
    assert "refreshBalance" in m and "refreshBalanceForWallet" in m
    ids = re.findall(r"^\s+(n\w+)\[", m, re.M)
    assert len(ids) == len(set(ids)) == 2
