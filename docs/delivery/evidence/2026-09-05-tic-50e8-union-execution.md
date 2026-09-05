# `tic-50e8` UNION execution review candidate

Status: **focused accepted; joint affected-module integration gate pending**

## Decision

The review candidate restores the existing UNION executor tests to River's
mandatory runtime-backed materialized-store contract. All nine focused cases
pass without changing parser, binding, UNION node execution, type coercion,
ordering, distinctness, or resource policy.

The candidate remains `in_progress`. `tic-50e8` and `tic-5cc0` repair
independent clean-master regressions and neither formally depends on the other.
The complete module run also exposed two independently reproduced clean-master
regressions which require separate P0 tickets: wide-decimal spilled ordering and
group-commit failure fencing. No such ticket is created here, and none of those
behaviors is absorbed into `tic-50e8`. All independently owned fixes must be
present at one joint integration gate before the affected `river-engine` suite
can be reported green.

## Independent review disposition

Independent review rejected candidate `8c71502` on evidence and process only;
its implementation static review was sound. This follow-up resolves all three
findings without amending that commit:

1. The ticket root cause now says the parser and set topology were valid and
   identifies the stale no-argument test constructor's absent runtime lease as
   the cause of materialized-store `INVALID_EXTERNAL_INPUT`.
2. The incorrect formal `tic-5cc0 -> tic-50e8` dependency is removed. There is
   no reverse dependency; both repairs converge only at the integration gate.
3. A complete `--no-fail-fast` candidate run and exact clean-baseline focused
   reproductions now disclose every observed failure, skip, hash, and the
   runner's terminal heap exhaustion.

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

The review-required complete module command was:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-50e8 \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-50e8 \
  :river-engine:test \
  --no-fail-fast
```

It ran for 10 minutes 22 seconds. Gradle reported 779 tests completed, 4
failures, and 1 skip, followed by a `Gradle Test Executor 4` failure caused by
`java.lang.OutOfMemoryError: Java heap space` while
`FaultingDurableDirectory.Entry` allocated its second configured 16 MiB byte
array. This is retained as a runner/resource observation: neither focused
baseline attribution run exhausted heap.

The four product-test failures were:

- `SqlSessionTest.namedSavepointCoexistsWithStatementRollback`, expected
  `RESOURCE_EXHAUSTED`, received `OK`; its complete-run class XML reports 34
  tests and 1 failure, SHA-256
  `3ee74625a54362b402f47d896e6636628b0d42bc290280fbdbb89514ad87b927`;
- `SqlWideNullPropagationTest.spilledWideDecimalSortPreservesAndOrdersBothLanes`,
  expected `OK`, received `CONFLICT`; its class XML reports 7 tests and 1
  failure, SHA-256
  `1242feaf0c9615b0394fbea55d06b0daeb79474c3280be39de5982ec326ce2ee`;
- `IndexedGroupCommitFaultTest` `file write`, expected `FENCED`, received `OK`;
  and
- the same parameterized test's `file force` case, expected `FENCED`, received
  `OK`. Its class XML reports 4 tests, 2 failures, and 1 skip, SHA-256
  `aa73e061b95d345831f0aa4b503268747d6ecdc35efa628d3263ee5c42582cac`.

The sole skipped case was
`IndexedGroupCommitFaultTest.forcedGroupInstallationFailureTerminalizesFencesAndRecoversExactlyOnce`.

The complete run's `SqlUnionExecutionTest` XML reports 9 tests, 0 failures, 0
errors, and 0 skips, SHA-256
`ff8f564c3b1524423ffc3d03d9ccc9280443669b2789d68c652dd0dcf827af51`.

## Additional clean-baseline attribution

The untouched detached baseline worktree was verified clean at exact commit
`9f756561f79d1ad0952c0ff4d38c07f670badd31`. The wide-decimal discriminator
used new isolated caches:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-50e8-baseline-wide \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-50e8-baseline-wide \
  :river-engine:test \
  --tests io.riverdb.engine.sql.SqlWideNullPropagationTest.spilledWideDecimalSortPreservesAndOrdersBothLanes
```

It exited 1 after 1 test and 1 failure with no skip or memory failure, matching
the candidate's line 98 `OK`/`CONFLICT` signature. XML SHA-256:
`f37c6205b2b3ebccdda0e90e63f2d170490e23e4de7c641275cc13453d5c4149`.

The group-commit discriminator first exited 1 after enumerating 2 cases under
repository fail-fast: `file write` failed with `FENCED`/`OK` and `file force`
was skipped. That XML SHA-256 was
`9f056e429ccdc37fa01888519353639a6d65bfe4dcf82a1362fb999eb9c1b9c5`.
The required complete rerun was:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-50e8-baseline-group \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-50e8-baseline-group \
  :river-engine:test \
  --tests io.riverdb.engine.table.IndexedGroupCommitFaultTest.groupedFacadeCommitFailureDoesNotPublishAndFencesAdmission \
  --no-fail-fast \
  --rerun-tasks
```

It exited 1 after both parameter cases failed with no skip or memory failure;
each matched the candidate's line 265 `FENCED`/`OK` signature. XML SHA-256:
`6c89fb7238a7cbc0314b4079f63fb4656d0a2a8350f1c3098fd9b715a2d9ed64`.

These exact clean reproductions prove the additional failures are not caused by
the UNION candidate. They require separate P0 ownership before the shared
module gate; this delivery neither creates those tickets nor changes their
production or test code.

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
and `tic-5cc0` independently owns its fix. No savepoint, session, transaction,
or test file for that behavior changed here. This evidence creates no
dependency in either direction. The accepted UNION and savepoint candidates,
plus separately ticketed wide-decimal and group-commit fixes, converge only at
the shared module integration gate.

## Files and scope

Implementation changes are limited to:

- `river-engine/src/main/java/io/riverdb/engine/sql/SqlUnionExecution.java`;
- `river-engine/src/main/java/io/riverdb/engine/sql/SqlBlockRowStore.java`; and
- `river-engine/src/test/java/io/riverdb/engine/sql/SqlUnionExecutionTest.java`.

Ticket administration and evidence are limited to `tic-50e8`, removal of the
incorrect dependency edge from `tic-5cc0`, the ordered Kanban frontier, and
this record. No compatibility path, second executor, weakened live semantic
assertion, parser change, or resource-policy change was introduced. Slopmark
was not run because the two production changes only delete invalid/dead
package-private entry points and do not alter a hot path.
