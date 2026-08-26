# Codemap

An offline, regenerable map of this codebase: every module, type and function across the
Rust light client, the JNI bridge, the Kotlin app and the Compose UI, as connected nodes.

Built to answer one question directly -- *if I change this, what is affected?* -- ahead of
the Kotlin Multiplatform split for iOS.

## Running it

```bash
./tools/codemap/run.sh          # writes out/codemap.html, ~2s
open tools/codemap/out/codemap.html
```

Then open the file. No server, no network, no build. Useful flags:

| Flag | Effect |
|---|---|
| `--watch` | rebuild whenever a source file changes |
| `--serve 8777` | localhost preview with the page reachable over http |
| `--stats-only` | skip HTML, just print the numbers |
| `--emit-summary-requests` | write the summary round-trip request file |
| `--import-summaries FILE` | merge generated summaries back in |

**The only step that touches the network is the one-time `pip install`** on first run.
Everything after that -- extraction, graph building, rendering, viewing -- is offline.
The graph libraries are vendored into `render/vendor/` and inlined into the page.

## Reading the map

**Two layouts over the same graph**, toggled in the toolbar, with selection and filters
preserved across the switch. They answer different questions:

- **Layered** puts each architectural layer in its own lane, left to right
  (Rust core → JNI → data → repository/DI → ViewModel → UI). Edges point from caller to
  callee, so dependencies normally flow right to left. A **red edge** is a backward edge --
  a lower layer depending on a higher one -- which is a layering violation.
- **Force** clusters by actual coupling, sizing nodes by fan-in. Coupling magnets surface
  on their own.

**Depth** starts at type level and opens at module level. Double-click any node to expand
just that subtree without unleashing all 1,900 function nodes. Edges lift to the nearest
visible ancestor, so a call between two functions still shows as an edge between their
containing types at type depth.

**Click a node** to dim everything else and highlight what it impacts (downstream) and
what it depends on (upstream), with counts in the side panel.

## The KMP overlay

Toggle it to colour every Kotlin node:

- **green** -- `commonMain`-ready
- **amber** -- needs an `expect`/`actual` seam
- **red** -- `androidMain` only

Red is reserved for **direct** evidence: the declaration itself references an Android-only
API, or carries a marker such as `external` (a JNI call by definition) or `@Composable`.
A clean function that merely *calls* a red one becomes amber, because it needs a seam
rather than being Android-bound itself.

That distinction is why the tool extracts at function level rather than file level. A
file-level graph would tell you `GatewayRepository.kt` is Android-bound, which you already
know and cannot act on. The unit that moves to `commonMain` is the method.

**The rules live in `rules/kmp.yaml`, not in code.** They are starting assumptions and
will be wrong in places -- Room 2.8.4 is multiplatform-capable but this project's usage is
unaudited, so it starts amber. Edit the file as the migration teaches you things.

### Baseline, 2026-08-26

Measured at commit time, for comparison as the migration proceeds:

| Class | Methods | green | amber | red |
|---|---:|---:|---:|---:|
| `GatewayRepository` | 90 | 14 | 46 | 30 |
| `TransactionBuilder` | 42 | 17 | 23 | 2 |
| `KeyManager` | 36 | 14 | 16 | 6 |
| `SyncCoordinator` | 8 | 3 | 0 | 5 |
| `DaoHeaderResolver` | 3 | 0 | 1 | 2 |
| **All Kotlin functions** | **1219** | **550** | **239** | **430** |

`TransactionBuilder` reads as the most portable substantial class (17 green, only 2 red),
which matches the M1 plan of moving molecule encoding and transaction assembly into shared
code first.

## What the edges mean

| Edge | Derivation | Confidence |
|---|---|---|
| `contains` | AST nesting | exact |
| `bridges` | `external fun` ↔ mangled `Java_com_*` symbol | exact |
| `injects` | `@Inject constructor` params ↔ types | exact |
| `imports` | import statements | exact |
| `calls` | callee name + receiver type from scope | **heuristic** |

`calls` is the only inexact edge, and the page shows its own error rate in the status bar.

### Call-resolution quality, 2026-08-26

```
2483 resolved · 271 in-scope failures (9.8%) · 19178 out of scope
```

Three outcomes, deliberately kept apart because conflating them makes the number
meaningless:

- **resolved** -- edge drawn.
- **out of scope** -- the callee is not in this codebase at all (Compose, Kotlin/Rust
  stdlib, Android SDK). Expected, not a failure. This is the large number.
- **in-scope failure** -- the receiver's type *is* one of ours but the method could not be
  located on it. This is the only number worth tracking, and it is what the status-bar chip
  reports.

Deliberately not resolved, because each needs real type inference: generic type
parameters, lambda receivers, scope functions that rebind `this` (`apply`/`let`/`run`/
`with`), and extension-function dispatch. The residual failures are dominated by
sealed-class constructor invocations (`Resource`, `Raw`, `Success`).

If that 9.8% ever becomes limiting, the escape hatch is a bytecode-grounded enrichment
pass over compiled classes -- deferred deliberately, since it would require a full Gradle
build per refresh and still miss Compose and Hilt indirection.

## Hover cards

Three tiers, in order of preference:

1. **Authored doc comment**, where one exists (27% of Kotlin declarations, 8% of Rust).
2. **Generated summary**, cached in `summaries/descriptions.json`, keyed by node id **plus
   a content hash of the declaration**.
3. **Mechanical evidence** -- signature, layer, `file:line`, fan-in/out, callers, callees,
   KMP class, bridge pairing. Always present, always true, no generation needed.

When a declaration's hash stops matching the hash its summary was written against, the
prose renders greyed with a **stale** badge and the evidence card carries the hover alone.
The tool degrades to terse-but-true rather than to confidently describing code that no
longer exists.

Generated summaries are the only part of this tool it cannot verify itself; everything else
derives from the AST. That asymmetry is shown in the UI rather than hidden.

### Summary round trip

```bash
./tools/codemap/run.sh --emit-summary-requests     # -> out/summary-requests.jsonl
# fill in a `text` field per record, writing out/summary-responses.jsonl
./tools/codemap/run.sh --import-summaries out/summary-responses.jsonl
```

A file exchange, not an API call: the tool never touches the network, and the workflow is
not tied to any particular model or service. Records whose hash no longer matches the graph
are rejected on import rather than filed against the wrong version.

## Tests

```bash
cd tools/codemap && .venv/bin/python -m pytest tests/ -v
```

66 tests over a fixture repo spanning Rust → bridge → repository → ViewModel → Composable,
including one live invariant: every `external fun` pairs to a Rust symbol and vice versa
(currently 24 ↔ 24, zero orphans).

## iOS and UniFFI

Swift extraction and UniFFI bridge stitching are already in place, built before `ios/`
exists so the map is correct from the first commit rather than retrofitted.

One graph spans both platforms:

```
Rust core --JNI-->    Kotlin --> Compose
          --UniFFI--> Swift  --> SwiftUI
```

`graph/bridge.py` carries two pairing schemes. JNI matches a Kotlin `external fun`
to its mangled `Java_com_*` symbol. UniFFI matches a Rust `#[uniffi::export]`
function to the Swift call site of its generated binding, applying UniFFI's
snake_case-to-camelCase conversion (`get_cell_count` becomes `getCellCount`).

The generated Swift bindings are build artifacts and are not in the repository, so
the edge runs from the Swift function that *calls* the binding straight to the Rust
function behind it. Orphans are reported on both sides: an `#[uniffi::export]` no
Swift code calls, or a Swift call to a binding with no Rust export.

Swift nodes render as orange triangles. `rules/layers.yaml` already carries
`uniffi`, `ios-core` and `ios-ui` lanes.

## CI

```bash
./tools/codemap/run.sh --check
```

Exits non-zero on any structural regression. Four invariants, configured in
`rules/checks.yaml`:

| Check | Fails when |
|---|---|
| `bridges_paired` | a JNI or UniFFI symbol loses its counterpart on either side |
| `no_layer_violations` | a lower layer starts depending on a higher one (leaf layers exempt) |
| `parse_errors` | a file outside the allow-list stops parsing |
| `unresolved_rate` | call resolution degrades past the configured ceiling |

Deliberately narrow: a red build must always mean something genuinely broke, never a
style disagreement. `.github/workflows/codemap.yml` runs it on PRs touching
`android/`, `external/`, `ios/` or the tool itself, and posts a bridge diagram to the
run summary.

## Exports

```bash
./tools/codemap/run.sh --mermaid out/bridge.mmd --focus nativeGetCells --depth-hops 1
```

Writes a Mermaid flowchart of one node's neighbourhood, grouped into layer subgraphs
with bridge edges drawn thick. Mermaid rather than an image because it renders
natively on GitHub, stays diffable in review, and needs no toolchain. Omit `--focus`
for the most-connected nodes at a given level.

## Known limitations

See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

## Vendored libraries

All MIT, committed under `render/vendor/` with checksums in `SHA256SUMS`, refreshed via
`render/vendor/fetch.sh`:

cytoscape 3.30.2 · dagre 0.8.5 · cytoscape-dagre 2.5.0 · layout-base 2.0.1 ·
cose-base 2.2.0 · cytoscape-fcose 2.2.0
