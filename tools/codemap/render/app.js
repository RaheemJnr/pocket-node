/* Codemap viewer.
 *
 * Two views over one graph:
 *   Lanes  -- deterministic preset positions, one labelled column per
 *             architectural layer. Positions are computed here rather
 *             than delegated to a layout engine, so the lanes are a
 *             guarantee instead of a hope.
 *   Force  -- fcose clustering for "what is actually coupled to what".
 *
 * Colour encodes TIER (Rust / JNI / Kotlin / UI): a four-slot palette,
 * the only one that validated all-pairs in BOTH light and dark. Its CVD
 * separation sits in the 6-8 floor band, so colour is never the sole
 * cue -- node SHAPE also encodes tier, and lane position encodes layer.
 */
(function () {
  "use strict";

  const DATA = JSON.parse(document.getElementById("codemap-data").textContent);
  const NODES = new Map(DATA.nodes.map((n) => [n.id, n]));
  const LAYER_META = DATA.stats.layer_meta || {};
  const SUMMARIES = DATA.summaries || {};
  const STRUCTURAL = new Set(["contains"]);

  const TIERS = {
    rust:   { layers: ["rust-core"] },
    jni:    { layers: ["jni"] },
    kotlin: { layers: ["data", "domain", "di", "util"] },
    ui:     { layers: ["viewmodel", "ui", "app"] },
  };
  const TIER_OF = {};
  for (const [t, def] of Object.entries(TIERS)) for (const l of def.layers) TIER_OF[l] = t;

  const LANE_ORDER = ["rust-core", "jni", "data", "domain", "di", "util", "viewmodel", "ui", "app"];

  const state = {
    depth: "type",
    layout: "lanes",
    kmp: false,
    violationsOnly: false,
    hideOrphans: true,
    groupByLayer: true,
    expanded: new Set(),
    selected: null,
    nodeScale: 1.2,
  };

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || "#888";
  }

  // ---- ancestry / lifting ---------------------------------------------

  const parentOf = new Map(DATA.nodes.map((n) => [n.id, n.parent]));

  function ancestors(id) {
    const out = [];
    let p = parentOf.get(id);
    while (p) { out.push(p); p = parentOf.get(p); }
    return out;
  }

  function liftTo(id, visible) {
    if (visible.has(id)) return id;
    for (const a of ancestors(id)) if (visible.has(a)) return a;
    return null;
  }

  function layerOrder(id) {
    const m = LAYER_META[id];
    return m ? m.order : -1;
  }

  function isBackward(e) {
    const a = layerOrder(NODES.get(e.src).layer);
    const b = layerOrder(NODES.get(e.dst).layer);
    return a >= 0 && b >= 0 && a < b;
  }

  // ---- sizing ----------------------------------------------------------

  const degree = new Map();
  for (const e of DATA.edges) {
    if (STRUCTURAL.has(e.kind)) continue;
    degree.set(e.dst, (degree.get(e.dst) || 0) + 1);
    degree.set(e.src, (degree.get(e.src) || 0) + 0.25);
  }

  const BASE = { module: 46, file: 30, type: 32, function: 26 };

  function nodeSize(el) {
    const kind = el.data("kind");
    if (kind === "layer") return 1;
    const d = degree.get(el.id()) || 0;
    return ((BASE[kind] || 26) + Math.min(40, Math.sqrt(d) * 7.5)) * state.nodeScale;
  }

  function labelSize(el) {
    const k = el.data("kind");
    if (k === "layer") return 22;
    const base = k === "module" ? 15 : k === "type" ? 13 : 12;
    return base * Math.max(0.9, Math.min(1.4, state.nodeScale));
  }

  // ---- visibility -------------------------------------------------------

  /* Depth is EXCLUSIVE. Reading it cumulatively drew modules AND files
   * AND types -- 770 nodes when 422 were the unit asked for, and 513 of
   * those had no edges at all, which is what collapsed the layout. */
  function visibleIds() {
    const vis = new Set();
    for (const n of DATA.nodes) {
      if (n.kind === state.depth) vis.add(n.id);
      else if (ancestors(n.id).some((a) => state.expanded.has(a))) vis.add(n.id);
    }
    return vis;
  }

  function buildElements() {
    const vis = visibleIds();
    const seen = new Set();
    const edgeEls = [];
    const touched = new Set();

    for (const e of DATA.edges) {
      if (STRUCTURAL.has(e.kind)) continue;
      const s = liftTo(e.src, vis);
      const t = liftTo(e.dst, vis);
      if (!s || !t || s === t) continue;
      const backward = isBackward(e);
      if (state.violationsOnly && !backward) continue;
      const key = s + "|" + t + "|" + e.kind;
      if (seen.has(key)) continue;
      seen.add(key);
      touched.add(s); touched.add(t);
      edgeEls.push({ data: { id: key, source: s, target: t, kind: e.kind,
                             confidence: e.confidence, backward: backward ? "yes" : "no" } });
    }

    const nodeEls = [];
    const lanes = new Set();
    for (const id of vis) {
      if (state.hideOrphans && !touched.has(id)) continue;
      const n = NODES.get(id);
      const parent = state.groupByLayer ? "layer:" + n.layer : null;
      if (parent) lanes.add(n.layer);
      nodeEls.push({ data: { id: id, label: n.name, kind: n.kind, lang: n.lang,
                             layer: n.layer, kmp: n.kmp,
                             tier: TIER_OF[n.layer] || "kotlin", parent: parent } });
    }

    const laneEls = [...lanes].map((l) => ({
      data: { id: "layer:" + l, kind: "layer",
              label: (LAYER_META[l] || {}).label || l, tier: TIER_OF[l] || "kotlin" },
    }));

    const present = new Set(nodeEls.map((n) => n.data.id));
    return {
      nodes: [...laneEls, ...nodeEls],
      edges: edgeEls.filter((e) => present.has(e.data.source) && present.has(e.data.target)),
    };
  }

  // ---- cytoscape --------------------------------------------------------

  const cy = cytoscape({
    container: document.getElementById("cy"),
    elements: [],
    wheelSensitivity: 0.2,
    style: [
      {
        selector: "node",
        style: {
          label: "data(label)",
          "font-size": labelSize,
          "font-weight": 500,
          "font-family": "ui-monospace, Menlo, monospace",
          color: cssVar("--fg"),
          "text-valign": "center",
          "text-halign": "right",
          "text-margin-x": 8,
          "text-outline-width": 3,
          "text-outline-color": cssVar("--bg"),
          "min-zoomed-font-size": 9,
          "border-width": 1.5,
          "border-color": "rgba(0,0,0,0.3)",
          width: nodeSize,
          height: nodeSize,
          "background-color": "#8b93a1",
        },
      },
      { selector: 'node[tier="rust"]',   style: { "background-color": "#2a78d6", shape: "diamond" } },
      { selector: 'node[tier="jni"]',    style: { "background-color": "#e34948", shape: "hexagon" } },
      { selector: 'node[tier="kotlin"]', style: { "background-color": "#1baf7a", shape: "ellipse" } },
      { selector: 'node[tier="ui"]',     style: { "background-color": "#008300", shape: "round-rectangle" } },
      {
        selector: 'node[kind="layer"]',
        style: {
          shape: "round-rectangle",
          "background-opacity": 0.05,
          "background-color": cssVar("--fg"),
          "border-width": 1,
          "border-color": cssVar("--border"),
          label: "data(label)",
          "text-valign": "top",
          "text-halign": "center",
          "text-margin-y": -10,
          "font-size": 22,
          "font-weight": 700,
          color: cssVar("--muted"),
          padding: 30,
        },
      },
      {
        selector: "edge",
        style: {
          width: 1.1,
          "line-color": "#8b93a1",
          "target-arrow-color": "#8b93a1",
          "target-arrow-shape": "triangle",
          "arrow-scale": 0.7,
          "curve-style": "bezier",
          opacity: 0.35,
        },
      },
      { selector: 'edge[kind="bridges"]', style: { width: 4, "line-color": "#e34948", "target-arrow-color": "#e34948", opacity: 1 } },
      { selector: 'edge[kind="injects"]', style: { "line-style": "dashed" } },
      { selector: 'edge[backward="yes"]', style: { "line-color": cssVar("--violation"), "target-arrow-color": cssVar("--violation"), width: 2.5, opacity: 0.95 } },
      { selector: ".kmp-green", style: { "background-color": cssVar("--green") } },
      { selector: ".kmp-amber", style: { "background-color": cssVar("--amber") } },
      { selector: ".kmp-red", style: { "background-color": cssVar("--red") } },
      { selector: ".kmp-unknown", style: { "background-color": "#5b6270" } },
      { selector: ".dim", style: { opacity: 0.06 } },
      { selector: ".sel", style: { "border-width": 4, "border-color": cssVar("--accent"), "z-index": 99 } },
      { selector: ".up", style: { "border-width": 3, "border-color": cssVar("--up") } },
      { selector: ".down", style: { "border-width": 3, "border-color": cssVar("--down") } },
      { selector: ".match", style: { "border-width": 4, "border-color": cssVar("--accent") } },
    ],
  });

  // ---- layout -----------------------------------------------------------

  /* Positions computed here, not delegated. cytoscape-dagre has no
   * per-node rank hook; passing one silently did nothing and every node
   * landed in a single column. */
  function lanePositions() {
    const buckets = new Map();
    cy.nodes().forEach((n) => {
      if (n.data("kind") === "layer") return;
      const l = n.data("layer");
      if (!buckets.has(l)) buckets.set(l, []);
      buckets.get(l).push(n);
    });

    const ROW = 64 * state.nodeScale;
    const COL = 250 * state.nodeScale;
    const MAX_ROWS = 26;

    const pos = new Map();
    let x = 0;
    for (const layer of LANE_ORDER) {
      const items = buckets.get(layer);
      if (!items || !items.length) continue;
      items.sort((a, b) => {
        const na = NODES.get(a.id()), nb = NODES.get(b.id());
        return (na.file || "").localeCompare(nb.file || "") || na.name.localeCompare(nb.name);
      });
      const cols = Math.ceil(items.length / MAX_ROWS);
      const rows = Math.ceil(items.length / cols);
      items.forEach((n, i) => {
        pos.set(n.id(), { x: x + Math.floor(i / rows) * COL, y: (i % rows) * ROW });
      });
      x += cols * COL + 200;
    }
    return pos;
  }

  function layout() {
    if (state.layout === "lanes") {
      const pos = lanePositions();
      cy.layout({ name: "preset", positions: (n) => pos.get(n.id()) || { x: 0, y: 0 },
                  fit: true, padding: 60 }).run();
    } else {
      cy.layout({ name: "fcose", quality: "proof", randomize: true, animate: false,
                  nodeRepulsion: 26000, idealEdgeLength: 130, gravity: 0.12,
                  nestingFactor: 0.4, numIter: 2500, fit: true, padding: 50 }).run();
    }
  }

  function applyKmpClasses() {
    cy.nodes().removeClass("kmp-green kmp-amber kmp-red kmp-unknown");
    if (!state.kmp) return;
    cy.nodes().forEach((n) => {
      if (n.data("kind") !== "layer") n.addClass("kmp-" + (n.data("kmp") || "unknown"));
    });
  }

  function render(runLayout) {
    const els = buildElements();
    cy.elements().remove();
    cy.add(els.nodes);
    cy.add(els.edges);
    applyKmpClasses();
    if (runLayout !== false) layout();
    updateCounts(els.nodes.filter((n) => n.data.kind !== "layer").length, els.edges.length);
    if (state.selected && cy.getElementById(state.selected).length) highlight(state.selected);
  }

  // ---- selection / impact -----------------------------------------------

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
      for (const nxt of adj.get(stack.pop()) || []) {
        if (!seen.has(nxt)) { seen.add(nxt); stack.push(nxt); }
      }
    }
    seen.delete(startId);
    return seen;
  }

  function highlight(id) {
    if (!NODES.has(id)) return;
    state.selected = id;
    const down = reachable(id, "down");
    const up = reachable(id, "up");

    cy.elements().addClass("dim");
    cy.elements().removeClass("sel up down");
    cy.nodes('[kind="layer"]').removeClass("dim");

    const present = new Set(cy.nodes().map((n) => n.id()));
    const show = (nid, cls) => {
      const lifted = liftTo(nid, present);
      if (!lifted) return;
      const el = cy.getElementById(lifted);
      if (el.length) { el.removeClass("dim"); if (cls) el.addClass(cls); }
    };

    show(id, "sel");
    down.forEach((n) => show(n, "down"));
    up.forEach((n) => show(n, "up"));
    cy.edges().forEach((e) => {
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

  // ---- panel -------------------------------------------------------------

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

    let html = '<h2 class="card-title">' + esc(n.name) + "</h2>";
    html += '<div class="card-sig">' + esc(n.signature || n.kind) + "</div>";

    if (n.doc) {
      html += '<div class="tier"><div class="tier-label">From source</div><div class="tier-body">'
            + esc(n.doc) + "</div></div>";
    }
    if (summary && summary.text) {
      const stale = summary.hash !== n.content_hash;
      html += '<div class="tier"><div class="tier-label">Generated</div><div class="tier-body'
            + (stale ? " stale" : "") + '">' + esc(summary.text) + "</div>";
      if (stale) html += '<span class="badge-stale">stale &mdash; code changed since written</span>';
      html += "</div>";
    }

    html += '<div class="tier"><div class="tier-label">Evidence</div><dl class="facts">'
      + "<dt>kind</dt><dd>" + esc(n.kind) + " &middot; " + esc(n.lang) + "</dd>"
      + "<dt>layer</dt><dd>" + esc((LAYER_META[n.layer] || {}).label || n.layer) + "</dd>"
      + "<dt>kmp</dt><dd>" + esc(n.kmp) + "</dd>"
      + "<dt>file</dt><dd>" + esc(n.file) + (n.start_line ? ":" + n.start_line : "") + "</dd>"
      + "<dt>fan-in</dt><dd>" + inN.length + "</dd>"
      + "<dt>fan-out</dt><dd>" + outN.length + "</dd>"
      + "<dt>impacts</dt><dd>" + downCount + " downstream</dd>"
      + "<dt>depends on</dt><dd>" + upCount + " upstream</dd>"
      + (n.bridge_symbol ? "<dt>bridge</dt><dd>" + esc(n.bridge_symbol) + "</dd>" : "")
      + "</dl></div>";

    const relList = (label, ids) => {
      if (!ids.length) return "";
      const items = ids.slice(0, 6).map((i) => {
        const t = NODES.get(i);
        return '<li data-goto="' + esc(i) + '">' + esc(t ? t.name : i) + "</li>";
      }).join("");
      const more = ids.length > 6 ? '<li class="meta">&hellip;' + (ids.length - 6) + " more</li>" : "";
      return '<div class="tier"><div class="tier-label">' + label + '</div><ul class="rel-list">'
           + items + more + "</ul></div>";
    };
    html += relList("Callers", inN) + relList("Callees", outN);

    const body = document.getElementById("panel-body");
    body.innerHTML = html;
    body.hidden = false;
    document.getElementById("panel-empty").hidden = true;
    body.querySelectorAll("[data-goto]").forEach((el) => {
      el.addEventListener("click", () => {
        const lifted = liftTo(el.getAttribute("data-goto"), new Set(cy.nodes().map((x) => x.id())));
        if (lifted) {
          highlight(lifted);
          cy.animate({ center: { eles: cy.getElementById(lifted) }, zoom: 1.1 }, { duration: 250 });
        }
      });
    });
  }

  function updateCounts(nodeCount, edgeCount) {
    document.getElementById("counts").textContent =
      nodeCount + " shown · " + edgeCount + " edges · " + DATA.nodes.length + " total";
  }

  // ---- wiring -------------------------------------------------------------

  cy.on("tap", "node", (e) => { if (e.target.data("kind") !== "layer") highlight(e.target.id()); });
  cy.on("tap", (e) => { if (e.target === cy) clearHighlight(); });
  cy.on("dbltap", "node", (e) => {
    if (e.target.data("kind") === "layer") return;
    const id = e.target.id();
    if (state.expanded.has(id)) state.expanded.delete(id); else state.expanded.add(id);
    render();
  });

  const on = (id, ev, fn) => document.getElementById(id).addEventListener(ev, fn);

  on("layout-lanes", "click", (e) => {
    state.layout = "lanes";
    e.target.classList.add("active");
    document.getElementById("layout-force").classList.remove("active");
    layout();
  });
  on("layout-force", "click", (e) => {
    state.layout = "force";
    e.target.classList.add("active");
    document.getElementById("layout-lanes").classList.remove("active");
    layout();
  });
  on("depth", "change", (e) => { state.depth = e.target.value; state.expanded.clear(); render(); });
  on("group-layer", "change", (e) => { state.groupByLayer = e.target.checked; render(); });
  on("hide-orphans", "change", (e) => { state.hideOrphans = e.target.checked; render(); });
  on("kmp-overlay", "change", (e) => {
    state.kmp = e.target.checked;
    document.getElementById("legend-kmp").hidden = !state.kmp;
    document.getElementById("legend-tier").hidden = state.kmp;
    applyKmpClasses();
  });
  on("violations-only", "change", (e) => { state.violationsOnly = e.target.checked; render(); });

  const sizeInput = document.getElementById("node-size");
  sizeInput.value = String(state.nodeScale);
  on("node-size", "input", (e) => {
    state.nodeScale = parseFloat(e.target.value);
    cy.style().update();
    if (state.layout === "lanes") layout();
  });

  on("search", "input", (e) => {
    const q = e.target.value.trim().toLowerCase();
    cy.nodes().removeClass("match");
    if (!q) { document.getElementById("search-count").textContent = ""; return; }
    const hits = cy.nodes().filter((n) => n.data("kind") !== "layer" && n.data("label").toLowerCase().includes(q));
    hits.addClass("match");
    document.getElementById("search-count").textContent = hits.length + " shown";
    if (hits.length) cy.animate({ fit: { eles: hits, padding: 90 } }, { duration: 300 });
  });

  // ---- zoom -------------------------------------------------------------

  const ZOOM_STEP = 1.35;

  function zoomBy(factor) {
    const z = Math.min(cy.maxZoom(), Math.max(cy.minZoom(), cy.zoom() * factor));
    const ext = cy.extent();
    cy.zoom({
      level: z,
      position: { x: (ext.x1 + ext.x2) / 2, y: (ext.y1 + ext.y2) / 2 },
    });
    showZoom();
  }

  function showZoom() {
    document.getElementById("zoom-level").textContent = Math.round(cy.zoom() * 100) + "%";
  }

  on("zoom-in", "click", () => zoomBy(ZOOM_STEP));
  on("zoom-out", "click", () => zoomBy(1 / ZOOM_STEP));
  on("zoom-fit", "click", () => { cy.fit(undefined, 60); showZoom(); });
  cy.on("zoom", showZoom);

  // keyboard, ignored while typing in the search box
  document.addEventListener("keydown", (e) => {
    if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT") return;
    if (e.key === "+" || e.key === "=") { zoomBy(ZOOM_STEP); e.preventDefault(); }
    else if (e.key === "-" || e.key === "_") { zoomBy(1 / ZOOM_STEP); e.preventDefault(); }
    else if (e.key === "0") { cy.fit(undefined, 60); showZoom(); e.preventDefault(); }
  });

  on("reset", "click", () => {
    state.expanded.clear();
    clearHighlight();
    document.getElementById("search").value = "";
    cy.nodes().removeClass("match");
    render();
  });

  function chip(elId, label, items) {
    const el = document.getElementById(elId);
    el.textContent = label;
    el.addEventListener("click", () => {
      const dlg = document.createElement("dialog");
      dlg.innerHTML = "<h3>" + esc(label) + "</h3><ol>"
                    + items.map((i) => "<li>" + esc(i) + "</li>").join("") + "</ol>";
      document.body.appendChild(dlg);
      dlg.addEventListener("click", () => { dlg.close(); dlg.remove(); });
      dlg.showModal();
    });
  }

  const uc = DATA.stats.unresolved_calls || { count: 0, top: [] };
  chip("chip-unresolved",
    "unresolved calls: " + uc.count + " (" + ((uc.rate || 0) * 100).toFixed(1) + "% in-scope)",
    (uc.top || []).map((p) => p[0] + " × " + p[1]));
  chip("chip-violations",
    "layer violations: " + (DATA.stats.violations || 0),
    DATA.edges.filter((e) => !STRUCTURAL.has(e.kind) && isBackward(e)).slice(0, 40)
      .map((e) => NODES.get(e.src).name + " → " + NODES.get(e.dst).name
                + "  (" + NODES.get(e.src).layer + " → " + NODES.get(e.dst).layer + ")"));

  document.getElementById("meta").textContent = DATA.meta.git_sha + " · " + DATA.meta.generated;
  render();
  showZoom();
})();
