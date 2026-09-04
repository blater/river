# `tic-50e8` UNION execution review candidate

Status: **focused accepted; affected-module gate blocked by `tic-5cc0`**

## Decision

The review candidate restores the existing UNION executor tests to River's
mandatory runtime-backed materialized-store contract. All nine focused cases
pass without changing parser, binding, UNION node execution, type coercion,
ordering, distinctness, or resource policy.

The candidate remains `in_progress`. The affected `river-engine` suite cannot
be reported green until it is combined with the separately owned `tic-5cc0`
savepoint resource-admission fix. The only module failure has the same method,
line, expected status, and actual status as that clean-master discriminator and
touches no file changed by this ticket.

## Root cause and replacement

The parser was not the failing boundary. Each query produced a populated set
expression, and the already-passing `describe` case traversed the same topology
and schema. Execution failed when `SqlBlockRowStore.begin` tried to open its
required materialized streams. The test-only no-argument
`SqlUnionExecution()` constructed `SqlSessionShapeBudget(null)`, so
`SqlMaterializedStatement.openStore` returned `INVALID_EXTERNAL_INPUT` before
any leaf opened.

The candidate deletes that unusable constructor and initializes every test
through the existing `SqlMaterializedTestFixture` and a real runtime lease. It
also deletes `SqlBlockRowStore.spilled()`, whose implementation was exactly
`rows != null` and whose only caller was an unreachable legacy assertion in
this test. Under mandatory materialization, the method distinguished only
whether required streams had opened, not whether execution crossed a spill
boundary. Retaining either member would preserve a second, invalid execution
contract; replacing them changes no production execution or resource policy.

## Commands and results

Both accepted runs used one exclusive Gradle lane in
`/private/tmp/river-tic-50e8` with isolated caches:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-50e8 \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-50e8 \
  :river-engine:test \
  --tests io.riverdb.engine.sql.SqlUnionExecutionTest
```

The final focused run completed successfully: 9 tests, 0 failures, 0 errors,
0 skips. Its XML SHA-256 was
`b719dede156b882358366cea11e3724b4584496829195f6cf65074a21df1c2d8`.
It covers simple and parenthesized `UNION`/`UNION ALL`, distinct/all boundaries,
leaf and root ordering/limits, integral and decimal reconciliation, null-aware
and Unicode equality, incompatible schemas, retained output, session-budget
accounting, and explain behavior.

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-50e8 \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-50e8 \
  :river-engine:test
```

The affected-module run compiled successfully and ran for 6 minutes 45 seconds
before the repository's fail-fast policy stopped it: 666 tests completed, 1
failed, and 1 was skipped. The sole failure was
`SqlSessionTest.namedSavepointCoexistsWithStatementRollback` at line 1122,
which expected `RESOURCE_EXHAUSTED` from the fourth savepoint and received
`OK`. The resulting `SqlSessionTest` XML reports 29 tests, 1 failure, and 1
skip, with SHA-256
`07ae206c55692c4bf455f0d443fe2db21b59c99f581743392cf945b7872fc363`.

The first sandboxed attempt at each command stopped before test execution: the
focused command could not download the pinned Gradle distribution, and the
module command could not create Gradle's local file-lock socket. Each accepted
rerun used the same command and caches with the required local permission.

## Baseline comparison

The clean pre-ticket `SqlUnionExecutionTest` artifact at
`/private/tmp/river-af29-baseline-9f75656/river-engine/build/test-results/test/TEST-io.riverdb.engine.sql.SqlUnionExecutionTest.xml`
reported 5 tests before fail-fast, 3 failures, and 1 skip. The three failures
expected `OK` and received `INVALID_EXTERNAL_INPUT` at lines 42, 60, and 101.
Its SHA-256 is
`68182236ce7d125085d25edcd2b1b6167dcd21dcfd9dcdf4c8c94a260c609083`.
The candidate focused artifact reports all 9 tests green.

The module failure is excluded from `tic-50e8`: clean master already records
the same exact savepoint-method failure with SHA-256
`92c4d946d7345afc377ffd1b0eb43120b26e8af1587348ca86fda4bcc32bbab4`,
and `tic-5cc0` owns its fix. No savepoint, session, transaction, or test file for
that behavior changed here.

## Files and scope

Implementation changes are limited to:

- `river-engine/src/main/java/io/riverdb/engine/sql/SqlUnionExecution.java`;
- `river-engine/src/main/java/io/riverdb/engine/sql/SqlBlockRowStore.java`; and
- `river-engine/src/test/java/io/riverdb/engine/sql/SqlUnionExecutionTest.java`.

Ticket administration and evidence are limited to `tic-50e8`, the dependency
edge in `tic-5cc0`, the ordered Kanban frontier, and this record. No compatibility
path, second executor, weakened live semantic assertion, parser change, or
resource-policy change was introduced. Slopmark was not run because the two
production changes only delete invalid/dead package-private entry points and do
not alter a hot path.
