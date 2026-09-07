---
id: tic-5cc0
status: closed
type: bug
assignee: blater
parent: tic-5db4
delivery: code
base-commit: "2216407"
branch: ticket/tic-5cc0-savepoint-admission-delivery
delivered-commit: 274968e8be4ddff0949086ea98e44dbe5ea4a81a
checkpoint-tag: perf-checkpoint-20260907-savepoint-admission
tags:
    - p0
    - sql
    - transactions
    - resources
created: 2026-09-04T22:00:53.349124Z
---
# Restore resource-accounted SQL savepoint admission

Clean master at `9f756561f79d1ad0952c0ff4d38c07f670badd31`
deterministically fails
`SqlSessionTest.namedSavepointCoexistsWithStatementRollback`: the fourth
savepoint now returns `OK` after the arbitrary three-savepoint cap was removed,
while retained savepoint arrays have no configured resource-admission boundary.

## Outcome

Named SQL savepoints grow beyond three only while their retained high-water
storage is admitted by the existing session-shape resource budget; rejection
occurs before relational savepoint state changes, and lifecycle cleanup reuses
or returns the admitted capacity exactly once.

## In Scope / Owning Mechanism

The SQL session's existing session-shape lease owns both savepoint capacity
admission and the reusable retained savepoint representation. All River-owned
savepoint callers use that single path.

## Non-goals

- Add a savepoint-specific quota, a replacement runtime budget, or another
  resource-admission service.
- Restore a fixed savepoint-count cap or tune TPC-C throughput.
- Redesign transaction savepoint semantics, statement rollback, or unrelated
  session-retained structures.

## Stop Conditions

Stop and raise a prerequisite rather than broadening this ticket if the
existing session-shape lease cannot charge every retained savepoint byte, or if
admission cannot be ordered before relational mutation. An unrelated failing
test is evidence for a separate ticket, not additional scope here.

## Maximum Change Shape

One admission/accounting path and one retained savepoint representation may
change, together with their River-owned callers and focused tests. Do not add a
feature flag, compatibility wrapper, alternate quota, or second storage path.

## Design

Replace the stale convenience-cap contract with savepoint retention admitted by
the existing session-shape resource budget. Preserve growable, reusable
high-water storage within that budget and return `RESOURCE_EXHAUSTED` before
relational savepoint mutation at the declared boundary. Release, rollback,
commit, and abort clear logical savepoints and reusable name contents while the
retained capacity remains honestly charged; session close returns its runtime
lease. Do not introduce a second quota system or reintroduce a fixed low
cardinality cap.

## Acceptance Criteria

Focused tests prove more than three named savepoints succeed when budget permits;
deterministic budget exhaustion returns `RESOURCE_EXHAUSTED` without corrupting
transaction state; release, rollback, commit, and abort clear logical state and
reuse admitted high-water capacity without double charging; session close
returns the runtime lease; the exact formerly failing `SqlSessionTest` method
and affected engine module suite pass. Record clean-master failure XML SHA-256
`92c4d946d7345afc377ffd1b0eb43120b26e8af1587348ca86fda4bcc32bbab4` and
accepted candidate evidence.

## Notes

### 2026-09-04T22:01:00Z

Clean-baseline discriminator: the exact focused test failed at
`9f756561f79d1ad0952c0ff4d38c07f670badd31`, expecting
`RESOURCE_EXHAUSTED` when the fourth `SAVEPOINT` returned `OK`. The XML is
retained at
`/private/tmp/river-tic-af29-evidence-20260904/allocation/baseline-9f756561-focused-SqlSessionTest-namedSavepoint.xml`
with SHA-256
`92c4d946d7345afc377ffd1b0eb43120b26e8af1587348ca86fda4bcc32bbab4`.
This was discovered during `tic-af29` affected-module verification;
`tic-af29` source was excluded by exact baseline reproduction.

## 2026-09-07 resumed delivery

The old feature commit is not on master. Adapt its accounting only, preserving
current program handling and observed-durability semantics. Fresh untouched
OpenJDK 26.0.2.1 baseline samples are 156.2/162.0 TPS; subsequent parent changes
are documentation-only. Evidence: `/private/tmp/river-tic-5cc0-evidence-20260907`.

## Implementation and validation

Implementation `3c338fc`; candidate measurement revision
`f20f2e14590ff4823bac1b719f4a74765fb03555` includes documentation-only master
updates. The existing session shape lease now reserves retained named-savepoint
arrays, name buffers, carriers and conservative SQL-owned lower-stack capacity
before relational mutation. SQL owns at most one statement savepoint in addition
to named capacity. High-water storage remains charged and reusable until lease
close; failed allocation returns only its uninstalled charge. Program handling,
statement rollback and observed durability behavior are unchanged.

Independent review accepted the exact adaptation and the strengthened tests.
The identical real SQL budget-boundary test fails on unchanged 2216407 with
expected RESOURCE_EXHAUSTED/actual OK, and passes after. Six resource tests plus
that boundary test and affected statement/savepoint regressions pass: 16 focused
cases. Clean full `./gradlew clean test --no-fail-fast` passed 1,788 tests with
zero failures and two skips in 7m42s; no retry or weakened threshold was needed.

Slopmark: SqlTransactionState 0->0; SqlSessionExecutionCoordinator 283.768->283.768.
The constructor passes the existing budget; no new technical responsibility or
hot-path allocation/copy is introduced. Source/bytecode policy checks retain
261 identical pre-existing violations, 0 added/removed. Indexed-table reference
verification passes. Full logs and XML are preserved in the evidence directory.

All four samples use OpenJDK 26.0.2.1 via the exact pinned launcher
`/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home/bin/java`,
seed 42, tiny standard mix, 10 terminals, 1 warehouse, serializable/no-wait stress,
32 maximum attempts, synchronous WAL, 1s warmup and 10s measured. Commands are
`tools/tps-test.sh --seed=42 --warmup-seconds=1 --measured-seconds=10
--output-dir=<evidence>/<sample>`, preceded by daemon-backed `./make.sh` and with
`RIVER_JAVA` pinned. Gradle used `/private/tmp/river-gradle-tic-f8dd` sequentially
with worktree-local project/build outputs; no parallel build/workload ran.

| Sample | TPS | Retries / errors | Receipt |
| --- | ---: | --- | --- |
| baseline-1 |156.2|0 /0|success /OK|
| baseline-2 |162.0|0 /0|success /OK|
| candidate-1 |164.0|0 /0|success /OK|
| candidate-2 |161.3|0 /0|success /OK|

Invariants, capture and terminal cleanup pass. Runtime launcher hashes, clean
source, source stability and identical configuration fingerprints were checked.
This is correctness/resource admission evidence with no independent speedup
claim and no repeated short-sample regression signal. User-declared background
PC load remains part of the diagnostic context; empty host-observation files
and the current receipts do not prove exclusive-host ownership. Broader P0
provenance/host and scaling gates remain open.

## Delivery

Merged/pushed at `274968e8be4ddff0949086ea98e44dbe5ea4a81a`, annotated tag
`perf-checkpoint-20260907-savepoint-admission`. Post-merge smoke passed
invariants/capture/shutdown with zero retries/errors; its3s TPS is not comparative.
