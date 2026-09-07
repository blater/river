---
id: tic-e2be
status: closed
type: bug
assignee: blater
delivery: code
base-commit: 56ccd9d317e135a9f06cd2ec022bba2160c43a71
branch: ticket/tic-e2be-wide-decimal-spill
delivered-commit: 78e42cacd857013b9f7fde644e5a2ce32d57cb77
tags:
    - p0
    - sql
    - relational
    - spill
    - correctness
created: 2026-09-05T00:11:48.386806Z
---
# Restore the spill-forcing wide DECIMAL128 acceptance test

Clean pre-ticket source `9f756561f79d1ad0952c0ff4d38c07f670badd31`
fails
`SqlWideNullPropagationTest.spilledWideDecimalSortPreservesAndOrdersBothLanes`:
its stale 1,025-row fixture calls `nextSpilled` even though the admitted input
remains resident, so it receives `CONFLICT` instead of the negative DECIMAL128
row.

The DECIMAL128 spill path is not the failing boundary. Resource-admitted sort
sizing raised this one-column workspace's resident run above the test's stale
1,024-row assumption, so `finish` correctly kept all 1,025 rows in memory and
the test incorrectly called the spilled-only reader.

## Design

Replace the stale fixed test boundary with the resident-run size already admitted
by `SqlSortWorkspace`. Fill that run, append one negative DECIMAL128 row to force
a second run, and retain the existing merge, two-lane read, order, primary-key,
and close assertions. Do not change production sort behavior or add an in-memory
alternate, convenience cardinality cap, second value representation, or
duplicate executor.

## Acceptance Criteria

A resource-boundary-derived, spill-forced DECIMAL128 input creates two admitted
runs, preserves and orders both high and low lanes, yields the negative row first
with primary key 2000, and closes all materialized resources. The focused method,
`SqlWideNullPropagationTest`, relevant sort/materialization tests, and affected
`river-engine` tests pass. Record clean-baseline XML SHA-256
`f37c6205b2b3ebccdda0e90e63f2d170490e23e4de7c641275cc13453d5c4149`
and independently reviewed candidate evidence.

## Notes

### 2026-09-05T00:11:48Z

Discovered after the `tic-50e8` non-fail-fast module discriminator exposed
tests beyond the known savepoint failure. The exact method reproduced in the
clean detached `9f756561f79d1ad0952c0ff4d38c07f670badd31` worktree as one test,
one failure, and no skip or OOM. The existing assertion expected `OK` and
received `CONFLICT`; the XML SHA-256 is
`f37c6205b2b3ebccdda0e90e63f2d170490e23e4de7c641275cc13453d5c4149`.

### 2026-09-05T01:07:09Z

Static diagnosis found that the default 64 MB test runtime admits 31 materialized
sort pages. With one non-text projection, the existing checked sizing policy
selects 30,507 resident rows, so 1,025 rows never enter the spill path. The
test's direct `nextSpilled` call therefore returns its correct empty-spill
`CONFLICT` before record decoding. The approved correction derives the boundary
from `workspace.configuredRunRows()`, appends one additional negative row, and
asserts that spill occurred. Production behavior remains unchanged; build
evidence is recorded in
`docs/delivery/evidence/2026-09-05-tic-e2be-wide-decimal-spill.md`.

### 2026-09-05T08:50:06Z

The exact corrected method passed as one test with no failure, error, or skip in
a 58-second isolated-cache Gradle invocation. Its testcase took 0.198 seconds;
the class XML SHA-256 is
`649ec2cb76e7151cb99f42637ee3add9b33fa3e418717213d3f326bd336b1c9d`.
The explicit spill assertion and the existing high-lane, low-lane, ordering,
primary-key, workspace-close, and runtime-close assertions all completed. No
resource or memory anomaly was reported. Wider gates have not run.

### 2026-09-05T08:55:59Z

The complete `SqlWideNullPropagationTest` class passed 7 tests with no failure,
error, or skip. The identical escalated retry completed in one second after the
first sandboxed invocation stopped before Gradle execution with a local
`FileLockContentionHandler` socket-permission error. The class XML SHA-256 is
`7720962c1705c0f755aed44b205f5b3684fc68083bdabd53afd6f91bf6c3b144`.
Post-test slopmark scores equal the recorded baseline because production is
unchanged. The ticket remains `in_progress` pending the joint affected-engine
integration gate; no module run is claimed here.

## 2026-09-07 delivered-source acceptance

Implementation `78e42cacd857013b9f7fde644e5a2ce32d57cb77` is on pushed master. Independent reviewer
`review_catalog_overlap` verified relevant current source/tests equal the accepted
f8dd checkpoint, and independently checked the retained XML: 7 class cases
pass, SHA-256 `29001d68692e1a95c44e3e04219a03d3cefdd55cbbba1e2fe624de5eda25cfc1`. The joint engine gate is 1,000 tests with no failures;
clean full gate is 1,781 tests, zero failures/errors, two skips. These are existing
checkpoint results, not a newly executed build.

Preserved evidence: `/private/tmp/river-p0-closure-evidence-20260907`, including
class XML, original clean log/summary and hash manifest. Checkpoint integration
`0cf9970f5371f520b6a9639424b4446d9e4c3412` and annotated tag
`perf-checkpoint-20260906-directory-cache-reload` are pushed ancestors.
