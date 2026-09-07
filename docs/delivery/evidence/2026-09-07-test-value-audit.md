# Test value and fragility audit

Source: `188012c` (2026-09-07). Ticket: `tic-37c1`.

Three Luna agents at high effort reviewed disjoint SQL/relational,
storage/concurrency, and client/protocol/tooling areas. The lead reviewed build
checks and reconciled findings across subsystem boundaries. This was a bounded
source audit, not exhaustive coverage analysis. No tests or builds were run and
no tests were removed. Structural fragility below is not a claim of observed
flakiness.

## Decisions

Prefer deleting redundant assertions and shrinking costly fixtures over merging
unrelated tests into large suites. Each removal must name the failure it can
currently catch and the retained test which will still catch it. Similar setup,
method names, or use of the same measurement API is not proof of duplication.

### First cleanup: remove duplicate assertions and improve weak checks

1. **Delete the protocol enum-count assertion.** In
   `ProtocolFrameCodecTest.preservesGoldenRequestAndResponseBytesForEveryMessageKind`,
   remove `assertEquals(15, ProtocolMessageType.values().length)`. The existing
   per-enum switch rejects uncovered kinds and the golden frames check actual
   wire codes. Preserve both, including the separately handled program-message
   cases. The count assertion adds no useful independent failure case.
   [Source](../../../river-protocol/src/test/java/io/riverdb/protocol/ProtocolFrameCodecTest.java).

2. **Remove repeated parser capacity construction.** Keep maximum identifier
   and trailing-input classification in
   `SqlParserTest.acceptsExactCapacitiesAndPreservesCapacityBeforeTrailingInput`.
   Its repeated table-column and predicate checks belong in
   `SqlShapeCapacityTest`, which already tests exact and plus-one limits and
   failed-command availability. Retain those named boundary assertions; do not
   delete the entire mixed test.
   [Parser tests](../../../river-sql/src/test/java/io/riverdb/sql/SqlParserTest.java),
   [shape tests](../../../river-sql/src/test/java/io/riverdb/sql/SqlShapeCapacityTest.java).

3. **Consolidate generated-value width tests.** Move the value-width assertions
   from `TpccLoaderShapeTest.generatedValuesStayInsideDeclaredWidthsAndLineBounds`
   into the existing `TpccValuesTest` cases. Replace the 10,000 repeated samples
   with explicit min/max and marker-present/absent cases plus a modest fixed-seed
   sweep. Keep loader placeholder validation and initial order-line counts at
   their existing owner. The survivor must check lower as well as upper widths;
   merely reducing the loop loses some input sampling and needs review.
   [Loader tests](../../../river-bench/src/test/java/io/riverdb/bench/tpcc/TpccLoaderShapeTest.java),
   [value tests](../../../river-bench/src/test/java/io/riverdb/bench/tpcc/TpccValuesTest.java).

These are small maintenance reductions, not a material speedup claim.

### Highest runtime opportunity: retain the boundary with a cheaper fixture

`SqlBlockRowPagedStoreTest.mergesMoreThanSixtyFourConfiguredRunsAndOddTail`
consumed **212.026 seconds** in the existing accepted full-build XML. It combines
more than 64 sort runs with a very small page cache and checks every output.
Start by using the already supported smaller page/run configuration and a cache
sized to avoid unrelated pathological I/O. Derive the input count from the
actual configured run capacity. Still require more than 64 runs, an odd tail,
correct ordering/public keys, and cleanup. Do not add production knobs or a
second sorter to make the test cheap.

The ordinary sort convergence test exercises a different path, so it is not a
sufficient replacement for this direct block-store test. Keep its stable ties,
descending order, and generated-text coverage. Measure the rewritten fixture
before claiming savings.
[Direct store tests](../../../river-engine/src/test/java/io/riverdb/engine/sql/SqlBlockRowPagedStoreTest.java),
[ordinary sort tests](../../../river-engine/src/test/java/io/riverdb/engine/sql/SqlOrdinaryExternalSortConvergenceTest.java).

Keep `SqlLargeOrdinalPublicExecutionTest` despite its **95.961 seconds**. It
proves public SQL ordering, checkpoint/reopen, cleanup, and a fallback join
beyond ordinal 65,535. Shrinking it below that boundary or replacing it with a
store unit test would remove the regression it exists to catch.
[Public ordinal test](../../../river-engine/src/test/java/io/riverdb/engine/sql/SqlLargeOrdinalPublicExecutionTest.java).

### Rewrite fragile checks without losing their invariants

- **Lock timing:** replace `lockWaitBlockedNanos() > 0` in
  `LockWaitObservabilityTest` with deterministic elapsed-time coverage of
  `LockWaitCounters.completeBlocked`, which already accepts explicit timestamps.
  Keep grant/timeout/cancel/deadlock counts and cross-manager isolation. Removing
  the assertion alone would leave duration accounting untested.
- **Deadlock coordination:** `IndexedLockWaitTest` polls thread state with a
  one-second deadline. Coordinate on actual lock-wait admission using existing
  observability/fixture facilities. Keep proof that the first edge is blocked
  before creating the cycle and keep borrowed-token revocation/cleanup checks.
  Do not add a production test hook or another wait implementation for convenience.
- **Descriptor-cache reflection:** replace exact private `shapeCache` lengths
  `{0,2,64}` in `IndexedRelationalWalHarnessTest` with bounded retention/accounting
  behavior. Its existing `accountedBytes` path is a candidate observation. Keep
  alternating descriptor decode, stale-shape rejection, and warmed allocation
  checks. Bounded memory is a real requirement, not dispensable implementation
  detail.
- **Root repair:** simplify `IndexedTableTest.insertsSplitsLooksUpAndReopens`
  around its unique post-flush root-page corruption and repair. Drop unnecessary
  exact page/root IDs and redundant split smoke only after retaining correct
  damaged-page selection, recovered rows, and a successful subsequent insert.

[Lock counters](../../../river-tx/src/test/java/io/riverdb/tx/LockWaitObservabilityTest.java),
[deadlock cleanup](../../../river-engine/src/test/java/io/riverdb/engine/table/IndexedLockWaitTest.java),
[descriptor tests](../../../river-engine/src/test/java/io/riverdb/engine/table/IndexedRelationalWalHarnessTest.java),
[root recovery](../../../river-engine/src/test/java/io/riverdb/engine/table/IndexedTableTest.java).

### Build and fixture overhead

`verifyProjectDependencyVisibility` in `build.gradle.kts` generates a synthetic
four-project graph and starts two nested Gradle builds on every invocation. Its
negative check also matches English compiler diagnostics. Restrict that expensive
compile proof to relevant build/dependency changes or replace it with a check of
actual River artifact visibility. Keep the real `verifyModuleGraph` checks and
compile-visibility evidence for approved API edges. Do not simply drop that
contract because a configuration-map test is cheaper. No measured task runtime
or savings is available from this audit.

Client and JDBC `TestTlsContexts` substantially duplicate certificate/key and
context setup; CLI has related material. A small shared test fixture may remove
copy drift. Preserve hostname mismatch, authentication, JDBC and CLI tests at
their own public boundaries. Do not build a general fixture framework or add
production dependencies to share test code.

## Suggestions rejected or made conditional by lead review

- **No blanket removal or opt-in demotion of allocation tests.** River explicitly
  requires hot-path allocation checks. Parser, session, codec, store, and WAL
  measurements cover different paths. Functional output tests do not replace
  them. Simplify the large session measurement harness only while preserving
  each distinct measured family and its resource/lifetime transition.
- **Do not replace parser reflection with the suggested getters.**
  `SqlCommand.joinChain()` hides retained zero-stage storage;
  `SqlQuery.block()` hides inactive blocks after reset. Those getters cannot
  prove the physical-retention invariant checked by
  `retainsAdmittedDeepestJoinArenaAndDiscardsRejectedTopology`. Keep this test
  until an equivalent observation is available without widening production APIs.
- **Do not blindly delete literal limit tests.** Boundary tests often derive
  their input from the same limit constant. They would still pass if someone
  lowered a documented capacity. Consolidate redundant internal aliases, but
  retain independent checks of promised capacities and structural relationships.
  `DatabaseResourceDefaultsTest` similarly guards the default, whereas the plan
  test passes an explicit capacity; those are not identical coverage.
- **Prepared JDBC batch deletion is conditional.** `RiverDriverTest` covers
  BIGINT batches and `RiverTypedParameterJdbcTest` covers typed snapshots, but
  the warehouse smoke combines SMALLINT, text and decimals in an explicit
  transaction. Preserve that combination in a retained batching case before
  removing its standalone server fixture.
- **SQLSTATE literals already have a separate guard.** `SqlStateTest` freezes
  temporal literals while `JdbcTemporalStatusTest` checks their mapping. The
  latter's use of production constants is not, by itself, a missing global
  contract check. Repeating every literal at every layer adds little value.
- **Separate counter owners still need tests.** WAL and group-commit capture
  tests can have similar reset/conflict cases while guarding independent
  implementations. Do not introduce shared test machinery merely for syntax.

Retain force-order/fencing/recovery matrices, independent B+tree model tests,
protocol mutation tests, authentication and terminal cleanup, snapshot/resource
admission failures, real lifecycle tests, and lease/rejection evidence. Each
covers a failure boundary absent from its superficially similar neighbors.

## Evidence and implementation boundary

Existing XML from `/private/tmp/river-invocation-host-ownership` contains 383
suites and 533.49 accumulated suite-seconds; some modules reused cached tests.
The two slow methods above total about 58% of that accumulated time, not elapsed
build time or predicted savings. The separate 65,537-row store boundary test
itself took **0.029 seconds**. Root-checkout XML is older and incomplete and was
not used as the full-suite census.

Raw scoped reports and timing inventory are retained under
`/private/tmp/river-test-value-audit-evidence-20260907`. Those reports contain
unfiltered agent recommendations; the lead decisions in this document govern.
The audit does not assert exhaustive coverage or measured flake rates.

Implement in small slices: duplicate-assertion cleanup first; expensive sort
fixture rewrite separately; concurrency/retention rewrites separately. Each
slice runs the affected tests, preserves its named survivor coverage, and
records before/after timings only where runtime is the objective. No test-count
reduction quota, new coverage framework, or broad test-suite rewrite is needed.
