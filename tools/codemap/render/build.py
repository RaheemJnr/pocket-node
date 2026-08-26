"""Bake the graph plus vendored libraries into one self-contained page.

Placeholder comments rather than str.format or f-strings: the CSS and JS
are full of braces.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
VENDOR_ORDER = [
    "cytoscape.min.js",
    "dagre.min.js",
    "cytoscape-dagre.js",
    "layout-base.js",
    "cose-base.js",
    "cytoscape-fcose.js",
]


def build_html(graph_dict: dict[str, Any]) -> str:
    template = (HERE / "template.html").read_text()
    css = (HERE / "styles.css").read_text()
    app = (HERE / "app.js").read_text()
    vendor = "\n;\n".join((HERE / "vendor" / n).read_text() for n in VENDOR_ORDER)

    # A stray </script> inside embedded JSON silently truncates the page.
    # This codebase's own comments discuss HTML, so it is not hypothetical.
    payload = json.dumps(graph_dict, separators=(",", ":")).replace("</", "<\\/")

    return (
        template
        .replace("/*STYLES*/", css)
        .replace("/*VENDOR*/", vendor)
        .replace("/*APP*/", app)
        .replace("/*DATA*/", payload)
    )
