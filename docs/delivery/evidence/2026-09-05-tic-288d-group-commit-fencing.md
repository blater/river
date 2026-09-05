# `tic-288d` group-commit fencing review candidate

Status: **focused class green; joint affected-engine gate pending**

## Root cause and ownership

The injected `FILE_WRITE`/`PARTIAL_WRITE` and
`FILE_FORCE`/`FORCE_FAILURE` paths already reach the canonical durability
owner. `IndexedHybridCommitGroup.append` fences the WAL and marks
`IndexedTableStore.failed` when storage may have changed; `force` marks the
store failed after an appended decision. `IndexedGroupCommitBatch.failGroup`
then calls synchronized group cancellation, which reasserts the WAL/store fence
and resets unpublished group state before transaction-manager failure
terminalization releases active transactions and locks. Only afterward does
`IndexedGroupCommitBatch.complete` publish the result through the request's
volatile completion ticket.

The live fence was therefore present and correctly ordered. It was bypassed by
`IndexedTableStore.transactionAdmissionStatus()`, which returned only durable
version pressure. Both session maintenance and `TransactionManager.begin`
reach that method through synchronized `IndexedTable.transactionAdmissionStatus`,
so a failed store incorrectly appeared healthy.

The candidate changes that one existing admission owner to return the full
store admission failure first, delegating to durable-version admission only
when the store is healthy. It adds no coordinator fence, terminalization path,
fallback, compatibility path, allocation, lock, or resource cap.

## Concurrency and recovery invariants

Append and cancellation pass through the synchronized table facade. A force
failure immediately passes through synchronized durability inspection and
cancellation, establishing the live store fence before manager cleanup and
before the volatile request completion wakes commit callers. A subsequent
synchronized transaction-admission read therefore observes the fence.

The `failed` flag is intentionally scoped to the live store instance. Reopen
constructs a fresh `IndexedTableStore`; `IndexedTableStoreConstruction.open`
publishes it only after `recoverFromWal()` and `flush()` succeed. This preserves
the recovery owner's authority to resolve the valid durable suffix and does not
turn a recoverable crash boundary into a permanent fence.

## Clean baseline

The untouched clean pre-ticket source at
`9f756561f79d1ad0952c0ff4d38c07f670badd31` ran both parameter cases under
`--no-fail-fast`: 2 tests, 2 failures, 0 errors, 0 skips, and no OOM. Both
failures expected `FENCED` from the new-session begin and received `OK`. Its XML
SHA-256 was
`6c89fb7238a7cbc0314b4079f63fb4656d0a2a8350f1c3098fd9b715a2d9ed64`.

## Candidate commands and results

Both accepted candidate runs used the exclusive Gradle lane in
`/private/tmp/river-tic-288d` with isolated caches.

The exact two-case command was:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-288d \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-288d \
  :river-engine:test \
  --tests 'io.riverdb.engine.table.IndexedGroupCommitFaultTest.groupedFacadeCommitFailureDoesNotPublishAndFencesAdmission' \
  --no-fail-fast \
  --rerun-tasks
```

It completed successfully in 1 minute 29 seconds. The XML reports 2 tests, 0
failures, 0 errors, and 0 skips: `file write` passed in 0.767 seconds and `file
force` passed in 0.549 seconds. Its SHA-256 was
`58006b613a1cde4317b0788c1467f9f32a0b573cb64ed644e2f25ce5d3a4ad26`.

The full-class command was:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-288d \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-288d \
  :river-engine:test \
  --tests io.riverdb.engine.table.IndexedGroupCommitFaultTest \
  --no-fail-fast
```

Its permitted retry completed successfully in 8 seconds. The XML reports 5
tests, 0 failures, 0 errors, and 0 skips; SHA-256
`adf21c0b079be9db767561f61cc660db9d8febac68573d917e259cfb8f0cd2ae`.
The shared artifact path for each fresh selected run was
`river-engine/build/test-results/test/TEST-io.riverdb.engine.table.IndexedGroupCommitFaultTest.xml`.

The first sandboxed exact-method wrapper attempt stopped before Gradle
configuration because DNS could not resolve the pinned distribution host. The
identical permitted retry downloaded Gradle 9.7.0 and ran the tests. The first
sandboxed full-class attempt also stopped before Gradle or test execution, this
time with a `FileLockContentionHandler` `SocketException`; its identical
permitted retry produced the accepted result. Neither pre-execution failure was
a product-test failure or OOM.

## Assertions and scope

The existing parameterized test continues to prove `IO_FAILURE` for both
accepted members, no commit-sequence or row publication, indeterminate
transaction/outcome state, and rejection of a new session with `FENCED`. It now
also proves direct store admission is `FENCED` and active and waiting lock
counts are zero before that new-session attempt.

Implementation and test changes are limited to:

- `river-engine/src/main/java/io/riverdb/engine/table/IndexedTableStore.java`;
- `river-engine/src/test/java/io/riverdb/engine/table/IndexedGroupCommitFaultTest.java`.

No batch, coordinator, hybrid-group, recovery, WAL, or resource-policy file was
changed. The review candidate remains `in_progress` until independent review
and the joint affected-engine integration gate are complete.

## Slopmark

Compact before/after scores over the traced production path:

| File | Before | After |
| --- | ---: | ---: |
| `IndexedTableStore.java` | 156.284 | 156.331 |
| `IndexedGroupCommitCoordinator.java` | 71.3503 | 71.3503 |
| `IndexedHybridCommitGroup.java` | 42.6842 | 42.6842 |
| `IndexedGroupCommitBatch.java` | 31.2329 | 31.2329 |
| `IndexedTable.java` | 19.3068 | 19.3068 |

The high store score remains a review signal. This change stays within its
existing admission responsibility and adds no new responsibility; every other
traced production file is untouched.
