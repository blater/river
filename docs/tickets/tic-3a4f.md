---
id: tic-3a4f
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDriverTest

File: `river-jdbc/src/test/java/io/riverdb/jdbc/RiverDriverTest.java`. Baseline slopwatch score: **166.674**.

## Approach

Organize the fixture and scenarios by the behavior they prove; start with `RiverDriverTest.driverManagerExecutesStreamingSqlTransactionsAndDurableReopen`, `RiverDriverTest.bigintComparisonsReachScansIndexesJoinsAggregatesAndMutations`, `RiverDriverTest.streamsLongCatalogNamesThroughJdbcAndReopens`. Share setup only where ownership and assertions stay explicit; remove redundant cases only with a named retained proof.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-jdbc` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Work notes

- Moved the streaming SQL/reopen, bigint comparison, and long catalog lifecycle
  suites into cohesive JDBC test classes.
- Added `RiverDriverTestFixture` to own embedded database/server lifetime and
  authenticated client setup, including durable reopen and failure cleanup.
- Baseline `RiverDriverTest.java`: 166.674.
- After unchanged full scan: all five affected files are below 90
  (`51.497`, `54.811`, `38.074`, `34.330`, and `10.352`); the scan analyzed
  2,526 files. Focused Gradle checks remain pending the lead's serialized build
  slot.

## Review fixes

- Migrated the remaining embedded JDBC tests onto the shared fixture and removed
  the duplicated client-file map, server starter, URL helper, token helper, and
  stray `@Test` annotation.
- Fixture startup now closes partially acquired resources on failure, cleanup
  attempts both server and database closes before reporting status, preserves a
  primary failure with cleanup suppressed, and clears the client-file path on
  close. Durable reopen retains the configured owner budget.
- Rescanned all changed files: scores remain below 90, with `RiverDriverTest`
  at `86.617` and the other four files unchanged.

## Compile and test review fix

- Restored the shared metadata row assertion through `JdbcMetadataAssertions`,
  restored the `Arrays` import used by batch assertions, and added the missing
  streaming result metadata/type imports.
- Focused compile, JDBC tests (`RiverDriverTest`, streaming, bigint, and
  catalog), and `:river-bench:installTps` all passed with isolated Gradle home
  and project cache. Log: `/private/tmp/river-score-20260911/tic-3a4f-tests.log`.
