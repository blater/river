---
id: tic-2e91
status: open
type: story
priority: 1
delivery: code
created: 2026-09-10
parent: tic-6d42
deps:
  - tic-a73c
---
# Use one focused unique-key lookup and reuse validated search state

## Change

After tic-a73c removes duplicate admission, profile its remaining unique-key probe.
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
