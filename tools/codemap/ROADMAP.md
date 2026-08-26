# Codemap roadmap

Deferred deliberately, with the reason. Revisit at the junction noted.

## Queued

### Impact-scoped test selection
Given changed files, name which of the 108 test files sit in the blast radius, so a
local run can be narrowed from the full suite.

**Blocked on:** call-graph completeness. At 9.8% in-scope resolution failures the tool
would occasionally omit a relevant test, and a test selector that silently skips the
one failing test is worse than no selector. Revisit if the rate drops below ~3%, or
alongside the bytecode enrichment pass.

### Public API surface tracking
Track and diff the exported surface of `commonMain`, so an accidental breaking change
to shared code is visible before Swift discovers it at compile time.

**Blocked on:** `commonMain` existing. Revisit at M1 once the shared module is real.

### Bytecode-grounded call enrichment
Optional `--enrich` pass using compiled classes for ground-truth call edges.

**Blocked on:** need. The current 9.8% failure rate is acceptable and the residue is
dominated by sealed-class constructor invocations. Revisit if the number climbs, or if
test selection above becomes wanted.

### Cross-branch graph diff
Compare two commits: nodes and edges added/removed, and functions that changed KMP
class. Useful as a per-PR "what moved to commonMain" report.

**Blocked on:** nothing technical. Sequenced after the migration work actually starts,
so the diff has something meaningful to show.

### KMP burndown over time
Per-commit green/amber/red snapshot, charted. Grant-reporting material.

**Blocked on:** nothing. Cheap whenever wanted; the data is already computed each run.

### Test coverage overlay
Colour nodes by whether tests reach them, next to the KMP colours.

**Blocked on:** a JaCoCo run in the pipeline. Heaviest item here; worth it only if
coverage becomes a tracked metric.

### Dead-code candidates
Nodes unreachable from any entry point.

**Blocked on:** trust. Heuristic call edges mean false positives, and a false positive
here reads as "safe to delete" for live code. Would need to ship as candidates with
evidence, never as a verdict.

## Shipped

- Swift extraction and UniFFI bridge pairing
- Structural CI checks (`--check`) and the PR workflow
- Mermaid subgraph export (`--mermaid`)
- KMP explain layer (`--why`) and migration planner (`--plan`)
- Dependency cycle detection (`--cycles`)
- Issue linking from code comments
