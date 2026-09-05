---
id: tic-e2be
status: open
type: bug
assignee: blater
delivery: code
tags:
    - p0
    - sql
    - relational
    - spill
    - correctness
created: 2026-09-05T00:11:48.386806Z
---
# Restore spilled wide DECIMAL128 ordering

Clean pre-ticket source 9f756561f79d1ad0952c0ff4d38c07f670badd31 fails SqlWideNullPropagationTest.spilledWideDecimalSortPreservesAndOrdersBothLanes: after 1025 rows force external materialization, the first nextSpilled call returns CONFLICT instead of the negative DECIMAL128 row.

## Design

Trace the existing materialized sort finish, run merge, and DECIMAL128 two-lane read path. Replace the stale boundary within the current sort/spill owner; do not add an in-memory alternate, convenience cardinality cap, second value representation, or duplicate executor.

## Acceptance Criteria

A spill-forced DECIMAL128 input preserves and orders both high and low lanes, yields the negative row first with primary key 2000, and closes all materialized resources. Focused method, SqlWideNullPropagationTest, relevant sort/materialization tests, and affected river-engine tests pass. Record clean-baseline XML SHA-256 f37c6205b2b3ebccdda0e90e63f2d170490e23e4de7c641275cc13453d5c4149 and independently reviewed candidate evidence.

## Notes

### 2026-09-05T00:11:48Z

Discovered after the `tic-50e8` non-fail-fast module discriminator exposed
tests beyond the known savepoint failure. The exact method reproduced in the
clean detached `9f756561f79d1ad0952c0ff4d38c07f670badd31` worktree as one test,
one failure, and no skip or OOM. The existing assertion expected `OK` and
received `CONFLICT`; the XML SHA-256 is
`f37c6205b2b3ebccdda0e90e63f2d170490e23e4de7c641275cc13453d5c4149`.
