---
id: tic-2e91
status: in_progress
type: story
priority: 1
delivery: code
created: 2026-09-10
branch: ticket/tic-2e91-unique-lookup
base: ad1db42f5d3d9dcdb6789111a99384041ccaec77
parent: tic-6d42
deps:
  - tic-a73c
---
# Use one focused unique-key lookup and reuse validated search state

## Change

After tic-a73c removes duplicate admission, profile its remaining unique-key probe.
Performance measurement is deferred to step 5; this slice covers correctness and
focused tests only.
Replace general prefix-cursor setup/advance/close work where the consumer needs
only the first matching live key. Reuse the existing B-tree search owner and the
validated tuple shape, key bounds and page information; do not add a parallel
B-tree executor or a global key/page validation cache.

Locate the key once per required visibility/root version. Compare an already
validated key without repeatedly decoding its arity, walking field boundaries or
revalidating unchanged bytes. Validate each newly admitted page/version before
trusting it. Preserve multi-column ordering, nullable unique semantics, logical
row suffixes and transaction-local insert/delete resolution. Continuation remains
available for callers that really need another matching row.

A located position may be reused only within its owning pin/latch/root lifetime.
This story must not hold a page latch across SQL execution or durable commit,
reuse a stale slot after a split, or change River's staged publication model just
to imitate InnoDB's cursor lifetime.

## Acceptance

- Record the remaining profile after tic-a73c and the exact repeated search or
  decode work eliminated. If that cost is no longer material, record the evidence
  and close without speculative optimization.
- Existing point/prefix consumers use one search implementation. Test absent and
  present keys, composite/prefix boundaries, duplicate candidates with local
  deletes, page boundaries/splits, changed root visibility and corrupt persisted
  key/page input. Reuse existing structural/recovery tests.
- No per-probe allocation or added payload copy. Follow parent INSERT/TPS,
  slopmark, correctness and checkpoint validation.

No persisted-format change, retained cross-transaction cursor cache, broad codec
rewrite or clustered-storage implementation.

## Implementation evidence

- Branch: `ticket/tic-2e91-unique-lookup`
- Base: `ad1db42f5d3d9dcdb6789111a99384041ccaec77`
- Correctness command:
  `./gradlew --no-daemon --project-cache-dir /private/tmp/river-project-cache-unique-lookup :river-format:test :river-storage:test :river-engine:test --tests io.riverdb.engine.table.IndexedTreeStructureTest --tests io.riverdb.engine.sql.SqlDescriptorTupleIndexScanTest`
- Result: `BUILD SUCCESSFUL` (27 actionable tasks; 10 executed, 17 up-to-date)
- Final touched slopmark: `TupleBTreeCursorAdvance=24.0979`,
  `TupleBTreeCursorOpen=5.68752`, `TupleBTreeTraversal=17.4932`,
  `TupleKeyPrefix=0`, `TupleBTreeLeafSearch=0`.
- Independent storage/recovery review: root review accepted the validated
  prefix state, one comparison path, binary leaf positioning, admission and
  root/page lifetime checks.
- Step 5 performance measurement remains pending by user instruction.
