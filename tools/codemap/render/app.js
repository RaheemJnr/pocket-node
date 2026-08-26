/* Codemap viewer.
 *
 * One graph, two layouts. Selection, filters and overlay state survive a
 * layout switch, because layered and force answer different questions
 * about the same click: layered gives the path and any layer violation,
 * force gives what is actually coupled.
 */
(function () {
  "use strict";

  const DATA = JSON.parse(document.getElementById("codemap-data").textContent);
  const NODES = new Map(DATA.nodes.map((n) => [n.id, n]));
  const LAYER_META = DATA.stats.layer_meta || {};
  const SUMMARIES = DATA.summaries || {};

  const DEPTH_RANK = { module: 0, file: 1, type: 2, function: 3 };
  const state = {
    depth: "type",
    layout: "layered",
    kmp: false,
    violationsOnly: false,
    expanded: new Set(),
    selected: null,
    nodeScale: 1.4,
  };

  // ---- element construction ------------------------------------------

  function layerOrder(id) {
    const m = LAYER_META[id];
    return m ? m.order : -1;
  }

  function isBackward(e) {
    const a = layerOrder(NODES.get(e.src).layer);
    const b = layerOrder(NODES.get(e.dst).layer);
    return a >= 0 && b >= 0 && a < b;
  }

  const STRUCTURAL = new Set(["contains"]);

  const elements = [];
  for (const n of DATA.nodes) {
    elements.push({
      data: {
        id: n.id,
        label: n.name,
        kind: n.kind,
        lang: n.lang,
        layer: n.layer,
        kmp: n.kmp,
        parent: null,
        rank: DEPTH_RANK[n.kind],
      },
    });
  }
  for (const e of DATA.edges) {
    if (STRUCTURAL.has(e.kind)) continue;
    elements.push({
      data: {
        id: `${e.src}|${e.dst}|${e.kind}`,
        source: e.src,
        target: e.dst,
        kind: e.kind,
        confidence: e.confidence,
        backward: isBackward(e) ? "yes" : "no",
      },
    });
  }

  // ---- ancestry -------------------------------------------------------

  const parentOf = new Map(DATA.nodes.map((n) => [n.id, n.parent]));

  function ancestors(id) {
    const out = [];
    let p = parentOf.get(id);
    while (p) { out.push(p); p = parentOf.get(p); }
    return out;
  }

  /** Nearest ancestor visible at the current depth, for edge lifting. */
  function liftTo(id, visible) {
    if (visible.has(id)) return id;
    for (const a of ancestors(id)) if (visible.has(a)) return a;
    return null;
  }

  // ---- sizing ---------------------------------------------------------

  /* Fan-in drives node size, so coupling magnets read as magnets instead
   * of every node being the same dot. Degree counts non-structural edges
   * only -- containment would make every module look enormous. */
  const degree = new Map();
  for (const e of DATA.edges) {
    if (STRUCTURAL.has(e.kind)) continue;
    degree.set(e.dst, (degree.get(e.dst) || 0) + 1);
    degree.set(e.src, (degree.get(e.src) || 0) + 0.25);
  }

  const BASE = { module: 46, file: 30, type: 30, function: 22 };

  function nodeSize(el) {
    const kind = el.data("kind");
    const d = degree.get(el.id()) || 0;
    const grow = Math.min(38, Math.sqrt(d) * 7);
    return (BASE[kind] || 22) + grow * (kind === "function" ? 0.8 : 1);
  }

  function scaledSize(el) { return nodeSize(el) * state.nodeScale; }

  function labelSize(el) {
    const kind = el.data("kind");
    const base = kind === "module" ? 17 : kind === "file" ? 13 : kind === "type" ? 14 : 12;
    return base * Math.max(0.85, Math.min(1.5, state.nodeScale));
  }

  // ---- cytoscape ------------------------------------------------------

  const cy = cytoscape({
    container: document.getElementById("cy"),
    elements: [],
    wheelSensitivity: 0.2,
    style: [
      {
        selector: "node",
        style: {
          "background-color": "#8b93a1",
          label: "data(label)",
          "font-size": labelSize,
          "font-weight": 500,
          "font-family": "ui-monospace, Menlo, monospace",
          color: cssVar("--fg"),
          "text-valign": "center",
          "text-halign": "right",
          "text-margin-x": 7,
          "text-outline-width": 2.5,
          "text-outline-color": cssVar("--bg"),
          "min-zoomed-font-size": 9,
          "border-width": 1,
          "border-color": "rgba(0,0,0,0.25)",
          width: scaledSize,
          height: scaledSize,
        },
      },
      { selector: 'node[kind="module"]', style: { "font-weight": 700 } },
      { selector: 'node[kind="file"]', style: { shape: "round-rectangle" } },
      { selector: 'node[kind="type"]', style: { shape: "round-rectangle" } },
      { selector: 'node[lang="rust"]', style: { "background-color": "#b45309" } },
      { selector: 'node[lang="kotlin"]', style: { "background-color": "#6366f1" } },
      { selector: 'node[layer="jni"]', style: { "background-color": "#059669", "border-width": 2, "border-color": "#34d399" } },
      {
        selector: "edge",
        style: {
          width: 1,
          "line-color": "#8b93a1",
          "target-arrow-color": "#8b93a1",
          "target-arrow-shape": "triangle",
          "arrow-scale": 0.6,
          "curve-style": "bezier",
          opacity: 0.45,
        },
      },
      { selector: 'edge[kind="bridges"]', style: { width: 3, "line-color": "#059669", "target-arrow-color": "#059669", opacity: 1 } },
      { selector: 'edge[kind="injects"]', style: { "line-style": "dashed" } },
      { selector: 'edge[backward="yes"]', style: { "line-color": cssVar("--violation"), "target-arrow-color": cssVar("--violation"), width: 2, opacity: 0.9 } },
      { selector: ".kmp-green", style: { "background-color": cssVar("--green") } },
      { selector: ".kmp-amber", style: { "background-color": cssVar("--amber") } },
      { selector: ".kmp-red", style: { "background-color": cssVar("--red") } },
      { selector: ".kmp-unknown", style: { "background-color": "#4b5563" } },
      { selector: ".dim", style: { opacity: 0.07 } },
      { selector: ".sel", style: { "border-width": 3, "border-color": cssVar("--accent"), "font-size": 12, "z-index": 99 } },
      { selector: ".up", style: { "border-width": 2, "border-color": cssVar("--up") } },
      { selector: ".down", style: { "border-width": 2, "border-color": cssVar("--down") } },
      { selector: ".match", style: { "border-width": 3, "border-color": cssVar("--accent") } },
    ],
  });

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || "#888";
  }

  // ---- rendering ------------------------------------------------------

  function visibleIds() {
    const maxRank = DEPTH_RANK[state.depth];
    const vis = new Set();
    for (const n of DATA.nodes) {
      const shown = DEPTH_RANK[n.kind] <= maxRank ||
        ancestors(n.id).some((a) => state.expanded.has(a));
      if (shown) vis.add(n.id);
    }
    // never show a node whose parent is hidden and unexpanded
    return vis;
  }

  function render(runLayout) {
    const vis = visibleIds();

    const nodeEls = [];
    for (const id of vis) {
      const n = NODES.get(id);
      nodeEls.push({
        data: { id, label: n.name, kind: n.kind, lang: n.lang, layer: n.layer, kmp: n.kmp },
      });
    }

    const seen = new Set();
    const edgeEls = [];
    for (const e of DATA.edges) {
      if (STRUCTURAL.has(e.kind)) continue;
      const s = liftTo(e.src, vis);
      const t = liftTo(e.dst, vis);
      if (!s || !t || s === t) continue;
      const backward = isBackward(e);
      if (state.violationsOnly && !backward) continue;
      const key = `${s}|${t}|${e.kind}`;
      if (seen.has(key)) continue;
      seen.add(key);
      edgeEls.push({
        data: {
          id: key, source: s, target: t, kind: e.kind,
          confidence: e.confidence, backward: backward ? "yes" : "no",
        },
      });
    }

    cy.elements().remove();
    cy.add(nodeEls);
    cy.add(edgeEls);
    applyKmpClasses();
    if (runLayout !== false) layout();
    updateCounts(nodeEls.length, edgeEls.length);
    if (state.selected && cy.getElementById(state.selected).length) {
      highlight(state.selected);
    }
  }

  function layout() {
    const opts = state.layout === "layered"
      ? {
          name: "dagre", rankDir: "LR", nodeSep: 26, rankSep: 190, edgeSep: 10,
          ranker: "longest-path", fit: true, padding: 30,
          // rank by architectural layer so lanes read left to right
          rank: (n) => layerOrder(NODES.get(n.id()).layer),
        }
      : {
          name: "fcose", quality: "default", randomize: false, animate: false,
          nodeRepulsion: 22000, idealEdgeLength: 105, gravity: 0.15, fit: true, padding: 30,
        };
    cy.layout(opts).run();
  }

  function applyKmpClasses() {
    cy.nodes().removeClass("kmp-green kmp-amber kmp-red kmp-unknown");
    if (!state.kmp) return;
    cy.nodes().forEach((n) => n.addClass("kmp-" + (n.data("kmp") || "unknown")));
  }

  // ---- selection / impact --------------------------------------------

  function reachable(startId, direction) {
    const adj = new Map();
    for (const e of DATA.edges) {
      if (STRUCTURAL.has(e.kind)) continue;
      const from = direction === "down" ? e.src : e.dst;
      const to = direction === "down" ? e.dst : e.src;
      if (!adj.has(from)) adj.set(from, []);
      adj.get(from).push(to);
    }
    const seen = new Set();
    const stack = [startId];
    while (stack.length) {
      const cur = stack.pop();
      for (const nxt of adj.get(cur) || []) {
        if (!seen.has(nxt)) { seen.add(nxt); stack.push(nxt); }
      }
    }
    seen.delete(startId);
    return seen;
  }

  function highlight(id) {
    state.selected = id;
    const down = reachable(id, "down");
    const up = reachable(id, "up");

    cy.elements().addClass("dim");
    cy.elements().removeClass("sel up down");

    const show = (nid, cls) => {
      const lifted = liftTo(nid, new Set(cy.nodes().map((n) => n.id())));
      if (!lifted) return;
      const el = cy.getElementById(lifted);
      if (el.length) { el.removeClass("dim"); if (cls) el.addClass(cls); }
    };

    show(id, "sel");
    down.forEach((n) => show(n, "down"));
    up.forEach((n) => show(n, "up"));

    cy.edges().forEach((e) => {
      const s = e.source().id(), t = e.target().id();
      if (!e.source().hasClass("dim") && !e.target().hasClass("dim")) e.removeClass("dim");
    });

    renderPanel(id, up.size, down.size);
  }

  function clearHighlight() {
    state.selected = null;
    cy.elements().removeClass("dim sel up down");
    document.getElementById("panel-body").hidden = true;
    document.getElementById("panel-empty").hidden = false;
  }

  // ---- hover card / panel --------------------------------------------

  function neighbours(id, dir) {
    const out = [];
    for (const e of DATA.edges) {
      if (STRUCTURAL.has(e.kind)) continue;
      if (dir === "in" && e.dst === id) out.push(e.src);
      if (dir === "out" && e.src === id) out.push(e.dst);
    }
    return out;
  }

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"]/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  }

  function renderPanel(id, upCount, downCount) {
    const n = NODES.get(id);
    const inN = neighbours(id, "in");
    const outN = neighbours(id, "out");
    const summary = SUMMARIES[id];

    let html = `<h2 class="card-title">${esc(n.name)}</h2>`;
    html += `<div class="card-sig">${esc(n.signature || n.kind)}</div>`;

    // Tier 1: authored documentation
    if (n.doc) {
      html += `<div class="tier"><div class="tier-label">From source</div>
               <div class="tier-body">${esc(n.doc)}</div></div>`;
    }

    // Tier 2: generated summary, with staleness guard
    if (summary && summary.text) {
      const stale = summary.hash !== n.content_hash;
      html += `<div class="tier"><div class="tier-label">Generated</div>
               <div class="tier-body${stale ? " stale" : ""}">${esc(summary.text)}</div>`;
      if (stale) html += `<span class="badge-stale">stale &mdash; code changed since written</span>`;
      html += `</div>`;
    }

    // Tier 3: mechanical evidence, always present and always true
    html += `<div class="tier"><div class="tier-label">Evidence</div><dl class="facts">
      <dt>kind</dt><dd>${esc(n.kind)} &middot; ${esc(n.lang)}</dd>
      <dt>layer</dt><dd>${esc((LAYER_META[n.layer] || {}).label || n.layer)}</dd>
      <dt>kmp</dt><dd>${esc(n.kmp)}</dd>
      <dt>file</dt><dd>${esc(n.file)}${n.start_line ? ":" + n.start_line : ""}</dd>
      <dt>fan-in</dt><dd>${inN.length}</dd>
      <dt>fan-out</dt><dd>${outN.length}</dd>
      <dt>impacts</dt><dd>${downCount} downstream</dd>
      <dt>depends on</dt><dd>${upCount} upstream</dd>
      ${n.bridge_symbol ? `<dt>bridge</dt><dd>${esc(n.bridge_symbol)}</dd>` : ""}
    </dl></div>`;

    const relList = (label, ids) => {
      if (!ids.length) return "";
      const items = ids.slice(0, 5).map((i) => {
        const t = NODES.get(i);
        return `<li data-goto="${esc(i)}">${esc(t ? t.name : i)}</li>`;
      }).join("");
      const more = ids.length > 5 ? `<li class="meta">…${ids.length - 5} more</li>` : "";
      return `<div class="tier"><div class="tier-label">${label}</div><ul class="rel-list">${items}${more}</ul></div>`;
    };
    html += relList("Callers", inN);
    html += relList("Callees", outN);

    const body = document.getElementById("panel-body");
    body.innerHTML = html;
    body.hidden = false;
    document.getElementById("panel-empty").hidden = true;

    body.querySelectorAll("[data-goto]").forEach((el) => {
      el.addEventListener("click", () => {
        const target = el.getAttribute("data-goto");
        const lifted = liftTo(target, new Set(cy.nodes().map((x) => x.id())));
        if (lifted) { highlight(lifted); cy.animate({ center: { eles: cy.getElementById(lifted) } }, { duration: 200 }); }
      });
    });
  }

  // ---- status bar -----------------------------------------------------

  function updateCounts(nodeCount, edgeCount) {
    document.getElementById("counts").textContent =
      `${nodeCount} nodes · ${edgeCount} edges shown · ${DATA.nodes.length} total`;
  }

  // ---- wiring ---------------------------------------------------------

  cy.on("tap", "node", (evt) => highlight(evt.target.id()));
  cy.on("tap", (evt) => { if (evt.target === cy) clearHighlight(); });
  cy.on("dbltap", "node", (evt) => {
    const id = evt.target.id();
    if (state.expanded.has(id)) state.expanded.delete(id);
    else state.expanded.add(id);
    render();
  });

  document.getElementById("layout-layered").addEventListener("click", (e) => {
    state.layout = "layered";
    e.target.classList.add("active");
    document.getElementById("layout-force").classList.remove("active");
    layout();
  });
  document.getElementById("layout-force").addEventListener("click", (e) => {
    state.layout = "force";
    e.target.classList.add("active");
    document.getElementById("layout-layered").classList.remove("active");
    layout();
  });

  document.getElementById("depth").addEventListener("change", (e) => {
    state.depth = e.target.value;
    state.expanded.clear();
    render();
  });

  document.getElementById("kmp-overlay").addEventListener("change", (e) => {
    state.kmp = e.target.checked;
    document.getElementById("legend").hidden = !state.kmp;
    applyKmpClasses();
  });

  document.getElementById("violations-only").addEventListener("change", (e) => {
    state.violationsOnly = e.target.checked;
    render();
  });

  document.getElementById("search").addEventListener("input", (e) => {
    const q = e.target.value.trim().toLowerCase();
    cy.nodes().removeClass("match");
    if (!q) { document.getElementById("search-count").textContent = ""; return; }
    const hits = cy.nodes().filter((n) => n.data("label").toLowerCase().includes(q));
    hits.addClass("match");
    document.getElementById("search-count").textContent = `${hits.length} shown`;
  });

  const sizeInput = document.getElementById("node-size");
  sizeInput.value = String(state.nodeScale);
  sizeInput.addEventListener("input", (e) => {
    state.nodeScale = parseFloat(e.target.value);
    cy.style().update();
  });

  document.getElementById("reset").addEventListener("click", () => {
    state.expanded.clear();
    clearHighlight();
    render();
  });

  function chip(elId, label, items) {
    const el = document.getElementById(elId);
    el.textContent = label;
    el.addEventListener("click", () => {
      const dlg = document.createElement("dialog");
      dlg.innerHTML = `<h3>${esc(label)}</h3><ol>${items.map((i) => `<li>${esc(i)}</li>`).join("")}</ol>`;
      document.body.appendChild(dlg);
      dlg.addEventListener("click", () => { dlg.close(); dlg.remove(); });
      dlg.showModal();
    });
  }

  const uc = DATA.stats.unresolved_calls || { count: 0, top: [] };
  chip("chip-unresolved",
    `unresolved calls: ${uc.count} (${((uc.rate || 0) * 100).toFixed(1)}% in-scope)`,
    (uc.top || []).map(([n, c]) => `${n} × ${c}`));
  chip("chip-violations",
    `layer violations: ${DATA.stats.violations || 0}`,
    DATA.edges.filter((e) => !STRUCTURAL.has(e.kind) && isBackward(e))
      .slice(0, 40)
      .map((e) => `${NODES.get(e.src).name} → ${NODES.get(e.dst).name}  (${NODES.get(e.src).layer} → ${NODES.get(e.dst).layer})`));

  document.getElementById("meta").textContent =
    `${DATA.meta.git_sha} · ${DATA.meta.generated}`;

  render();
})();
