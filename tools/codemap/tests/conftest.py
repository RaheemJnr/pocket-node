from pathlib import Path

import pytest

from extract.kotlin import extract_kotlin
from extract.rust import extract_rust
from graph.build import build_graph

FIX = Path(__file__).parent / "fixtures" / "mini"


def _raw_files():
    files = [extract_kotlin(p, f"mini/kotlin/{p.name}") for p in sorted((FIX / "kotlin").glob("*.kt"))]
    files += [extract_rust(p, f"mini/rust/{p.name}") for p in sorted((FIX / "rust").glob("*.rs"))]
    return files


@pytest.fixture
def mini_files():
    return _raw_files()


@pytest.fixture
def mini_graph(mini_files):
    """Containment only -- no decoration passes."""
    return build_graph(mini_files)


@pytest.fixture
def mini_full(mini_files):
    """Every decoration pass built so far, applied in order."""
    from graph import semantic as semantic_pass
    from graph import bridge as bridge_pass
    from graph import calls as calls_pass
    from graph import kmp as kmp_pass
    from graph import layers as layers_pass

    rules = Path(__file__).resolve().parent.parent / "rules"
    g = build_graph(mini_files)
    bridge_pass.apply(g, mini_files)
    calls_pass.apply(g, mini_files)
    semantic_pass.apply(g, mini_files)
    layers_pass.apply(g, rules / "layers.yaml")
    kmp_pass.apply(g, mini_files, rules / "kmp.yaml")
    return g
