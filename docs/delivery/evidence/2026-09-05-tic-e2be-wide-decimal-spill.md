# `tic-e2be` wide DECIMAL128 spill acceptance candidate

Status: **focused method and full class green; joint module gate pending**

## Root cause

The clean-baseline failure does not enter the materialized merge or DECIMAL128
decode path. `SqlSortWorkspace.begin` derives resident capacity from the
configured materialized pages and the session shape budget. Under the test's
default 64 MB runtime, 31 admitted 64 KB pages provide a 30,507-row resident run
for one non-text projection. The test appends only 1,025 rows.

`SqlSortWorkspace.finish` consequently takes its non-spilled branch and sorts
the rows in memory. It does not write the final run or call
`SqlSortSpill.initializeMerge`, so `SqlSortSpill.rowsRemaining` remains zero.
The test then bypasses the production caller's `isSpilled` branch and invokes
`nextSpilled`; the spilled reader correctly returns `CONFLICT` before decoding
a record.

The clean focused artifact recorded one test, one failure, and no skip or heap
exhaustion. It expected `OK` and received `CONFLICT`; XML SHA-256:
`f37c6205b2b3ebccdda0e90e63f2d170490e23e4de7c641275cc13453d5c4149`.

## Replacement

The candidate obtains the existing admitted boundary from
`workspace.configuredRunRows()`, fills that resident run with positive
DECIMAL128 values, and appends one negative value. That additional append writes
the full first run; `finish` writes the one-row second run and initializes the
existing paged merge. An explicit `workspace.isSpilled()` assertion prevents
this acceptance test from silently exercising the in-memory path again.

The existing assertions still require both decoded 64-bit lanes to equal `-1`,
the negative row to sort first, its primary key to equal `2000`, and workspace
and runtime closure to succeed. No production implementation, resource policy,
cardinality cap, value representation, or executor changes.

## Ownership and allocation

The resident boundary remains owned by `SqlSortAdmission`,
`SqlSortRunCapacity`, and `SqlSortRunStorage`; the test consumes their published
package-local boundary instead of duplicating it. The loop reuses one
`SqlProjectedRow`; retained arrays remain within the existing session shape
budget, while paged spill uses the runtime's existing page pool and sort
reservation. Merge-page reservation and stream cleanup remain under
`SqlSortSpillStreams`, and runtime teardown returns the existing materialized
statement and lease.

Implementation scope is limited to
`river-engine/src/test/java/io/riverdb/engine/sql/SqlWideNullPropagationTest.java`.
Ticket metadata and this evidence record are the only documentation changes.

## Static quality boundary

The focused pre-edit slopmark scores were:

- `SqlSortRunStorage`: 48.7397;
- `SqlSortSpillRecordReader`: 41.9434;
- `SqlSortWorkspace`: 19.8543;
- `SqlSortSpillHeadComparator`: 10;
- `SqlSortSpillMerge`: 5.35195;
- `SqlPagedExternalOrder`: 5;
- the remaining traced spill owner files: 0.

No production file is changed, so these scores are the before and candidate
values.

## Focused verification

The exclusive build lane ran only the corrected discriminator with isolated
caches:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-e2be \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-e2be \
  :river-engine:test \
  --tests io.riverdb.engine.sql.SqlWideNullPropagationTest.spilledWideDecimalSortPreservesAndOrdersBothLanes
```

The first sandboxed attempt stopped before Gradle execution because the pinned
distribution host was unavailable. The permitted rerun downloaded that
distribution and completed successfully in 58 seconds. The XML reports one
test, no failure, error, or skip, and a testcase duration of 0.198 seconds.
SHA-256:
`649ec2cb76e7151cb99f42637ee3add9b33fa3e418717213d3f326bd336b1c9d`.

Because the method would fail immediately if `workspace.isSpilled()` were false,
its successful completion proves that the configuration-derived input crossed
the admitted resident boundary. The subsequent unchanged assertions prove the
negative high and low lanes, first-row ordering, primary key `2000`, workspace
closure, and runtime closure. The XML has empty standard output and error; no
resource or memory anomaly was observed.

## Full-class verification

The next authorized gate used the same isolated caches:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-e2be \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-e2be \
  :river-engine:test \
  --tests io.riverdb.engine.sql.SqlWideNullPropagationTest
```

The sandboxed invocation stopped before Gradle or test execution because its
`FileLockContentionHandler` could not create a local socket. The identical
permitted retry completed successfully in one second. The XML reports 7 tests,
no failure, error, or skip, and total class time 0.356 seconds. Its SHA-256 is
`7720962c1705c0f755aed44b205f5b3684fc68083bdabd53afd6f91bf6c3b144`;
standard output and error are empty.

The post-test slopmark scores exactly match the pre-edit scores because no
production file changed. No module test was run. `tic-e2be` remains
`in_progress` until the independently owned P0 fixes meet their joint
`river-engine` integration gate.
