# tic-ca05: current logical admission acceptance

Date: 2026-09-14. Base: 699ddf5d; production equals 05c27d36.
Decision: existing logical contract satisfied, independently reviewed.

Paths below are relative to river-engine/src/main/java/io/riverdb/engine.

| Contract | Existing source and proof |
| --- | --- |
| Pre-queue preparation | table/IndexedGroupCommitCoordinator.commit calls prepareLogicalCommit before prepareCoordinatedCommit and enqueue. The transaction manager admits ACTIVE → PREPARED before queue ownership. |
| Logical seal | table/IndexedPreparedLogicalCommit retains borrowed session buffers with counts and generations; valid requires PREPARED/COMMITTING and unchanged generations. It is a logically sealed borrowed carrier, not a copied immutable image. IndexedTransactionSessionTest.preparedLogicalCommitRejectsSameCardinalityContentChange tests invalidation. |
| One sizing pass | table/IndexedHybridLogicalSizing measures logical WAL/version/compilation demand. Byte totals are long; integer-addressed carrier counts reject overflow. Physical preflight verifies versions exactly and WAL within the admitted bounds. |
| Authenticated receipts | table/IndexedTransactionResourceAdmission uses runtime/DatabaseResourceGovernor and one owner/generation ResourceLease. Retained workspace has its distinct session-lifetime DatabaseRetainedLease. Governor tests cover ownership, duplicate release, growth, impossible requests and transient pressure. |
| Direct/group equivalence | table/IndexedTransactionSession.commit uses the same logical preparation. Both paths reach IndexedHybridCommitGroup and IndexedHybridGroupPreflight for physical compilation. |
| Result lifetime | table/IndexedGroupCommitRequest owns its reusable outcome until completion. Interrupted accepted work waits for its actual outcome. |
| Cleanup and reuse | IndexedTransactionSession.completeTerminalCleanup resets the logical carrier and ends transaction admission; closeSession releases retained workspace. Existing admission and group-fault tests cover retry, held durability, failed preflight, force failure, terminal cleanup, recovery and reuse. |

## Exact handoff to existing tic-5b3e

Pre-queue ensureCommit supplies write entries, zero staged pages, versions and
WAL bytes. IndexedHybridGroupPreflight calls admitStagedPages only after
compileCumulative discovers the changed-page count. IndexedPreparedCommitCohortDemand
currently sums only version operations; coordinator drain selects by batch capacity.
This acceptance proves stable admitted logical demand, not complete pre-staging
cohort resource reservation or safe prefix selection. Those remain with tic-5b3e.
There is no extra ca05 code dependency or second logical representation to build.

## Validation

GraalVM 25; one serial --no-daemon build in /private/tmp/river-wal-ca05:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/river-b1b7-gradle \
./gradlew --no-daemon --project-cache-dir /private/tmp/river-wal-ca05-cache \
  :river-engine:test \
  --tests io.riverdb.engine.table.IndexedTransactionResourceAdmissionTest \
  --tests io.riverdb.engine.table.IndexedGroupCommitFaultTest \
  --tests io.riverdb.engine.runtime.DatabaseResourceGovernorTest
```

Passed: 1 + 20 + 22 = 43 tests, zero failures/errors. XML retained under
/Users/blater/src/river/benchmark-results/wal-ca05-20260914/.
Independent concurrency/admission reviewer accepted the map and the explicit
5b3e boundary. No new tests, production changes, TPS runs or speedup claim.
