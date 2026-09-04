# `tic-af29` lock-block causality implementation evidence

Status: **implementation candidate; matched 2/10 attribution pending**

Evidence class: generic lock-scheduler correctness, bounded observability,
allocation, and Java propagation evidence. This document does not claim that
the `tic-1dda` P0 revalidation has passed, does not accept a performance
checkpoint, and does not authorize a lock-policy change.

## Decision

The implementation provides one bounded, scheduler-owned classification for
each measured actual lock block. It identifies resource scope, requested mode,
held or queued-blocker mode, waiter and blocker queue relationship, and the
exact enforced grant precondition. The same ordered evaluator owns both grant
decisions and diagnostic description; diagnostics do not reimplement grant
policy.

Capture uses a fixed 432-counter primitive array and aggregate primitive
counters. It retains no per-block event. The disabled path reads one boolean at
the admission call site, reads no clock, and does not enter the descriptive
evaluator. A source bisection was required to establish this boundary after an
ordering-sensitive allocation regression was found; all failed and passing
evidence is retained below.

The required matched two- and ten-terminal standard diagnostics are not yet
valid to run. They must use the accepted `tic-0636` exact-source/classpath/build
and host-exclusion contract. Until that change is merged into this branch and
the workload lane is granted, `tic-af29` remains `in_progress`.

## Source and scope

- Exact pre-ticket source: `9f756561f79d1ad0952c0ff4d38c07f670badd31`.
- Claimed and pushed branch start:
  `9dcf779fa824f6680b583a0fb24651bcdc0d5653` on
  `ticket/tic-af29-lock-block-causality`.
- Worktree: `/private/tmp/river-tic-af29`.
- Gradle user home: `/private/tmp/river-gradle-tic-af29`.
- Gradle project cache: `/private/tmp/river-project-cache-tic-af29`.
- Clean baseline worktree: `/private/tmp/river-af29-baseline-9f75656` with
  fresh caches `/private/tmp/river-gradle-af29-baseline` and
  `/private/tmp/river-project-cache-af29-baseline`.
- No `tools/tps-*` file, provenance shell path, or
  `river-bench/build.gradle.kts` was edited; those belong to `tic-0636`.
- No workload, harness, clean build, merge, close, or tag was run or created.

## Scheduler ownership and causal model

`LockExactGrantDecision` is the sole ordered evaluator used by
`LockExactScheduler.canGrant`. Its false branches expose the first enforced
cause to active capture:

1. conversion queue head (`FIFO_QUEUE_HEAD`);
2. conversion priority over an ordinary waiter (`CONVERSION_QUEUE_EMPTY`);
3. ordinary exact-resource queue head (`FIFO_QUEUE_HEAD`);
4. earlier incompatible overlapping waiter
   (`NO_EARLIER_INCOMPATIBLE_WAITER`);
5. overlapping conversion priority (`CONVERSION_QUEUE_EMPTY`); and
6. incompatible active owner (`NO_INCOMPATIBLE_ACTIVE_OWNER`).

Classification calls that evaluator rather than duplicating its predicates.
For active owners the bucket records the held mode. For FIFO and conversion
blockers it records the blocking request's requested mode. Queue relationship
is derived from the enforced precondition, so an impossible combination is
rejected as unclassified rather than admitted to a bucket.

The fixed bucket dimensions are six `LockScope` values, three requested modes,
three blocker modes, two waiter queues, and four grant preconditions:
`6 * 3 * 3 * 2 * 4 = 432` counters. The finite counter limit reports overflow
and invalidates the phase; it never silently wraps. Capture reset is explicit
and phase-scoped. Detailed deadlock events keep their existing independent
bounded contract.

## Exact reconciliation

A quiescent capture is valid only when all of these hold:

```text
overflow = 0
unclassified = 0
failed = 0
entered = consumed + timed_out + cancelled + deadlocked + failed
handoffs = consumed + revoked_after_handoff
actual_blocks = bucket_total
actual_blocks = blocked_consumed + blocked_timed_out
              + blocked_cancelled + blocked_deadlocked + blocked_failed
victim_selections <= deadlocked
```

The engine additionally requires the legacy wait deltas to equal the new
entered, actual-block, handoff, timeout, cancellation, and deadlock counters.
It requires terminal retained snapshots to equal zero. The server now emits
both `server_retained_snapshots_at_capture` at the terminal boundary and
`server_capture_retained_snapshots` for the quiescent measured window. Existing
active-transaction, held-lock, and queued-waiter gauges remain unchanged.

Focused deterministic tests cover active-owner held mode, exact ROW FIFO,
conversion priority, overlapping-interval earlier-waiter fairness, successful
handoff and consume, timeout versus cancellation, deadlock victim selection,
counter overflow invalidation, phase reset, emitted metric serialization, and
zero terminal snapshots/transactions/locks/waiters.

## Commands and results

All Gradle commands used the ticket-specific user home and project cache and
ran only after the integration owner granted the exclusive build lane.
Sandbox-only daemon socket or DNS failures were retried outside the sandbox
with the same approved command; those startup failures did not execute tests.

The repeated focused transaction command was:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-af29 \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-af29 \
  :river-tx:test \
  --tests io.riverdb.tx.LockBlockCausalityTest \
  --tests io.riverdb.tx.LockWaitObservabilityTest \
  --tests io.riverdb.tx.LockExactAllocationTest
```

- The first compile exposed an unmatched bucket-index parenthesis. The second
  exposed a Java mutator/getter signature collision. Both were corrected before
  a test executed and are retained as console-reported development failures.
- The initial corrected focused run passed eight tests in four seconds.
- The same focused run passed after extracting the canonical grant owner in
  three seconds.
- After the disabled-capture guard fix, it passed all eight tests in one second
  (five causality, two wait-observability, one allocation).

Java propagation was checked with:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-af29 \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-af29 \
  :river-engine:test \
  --tests io.riverdb.engine.EmbeddedDatabaseTest \
  --tests io.riverdb.engine.EmbeddedLockBlockDiagnosticsTest \
  :river-bench:compileJava
```

After an initial sandbox socket denial, the identical approved command passed
in 19 seconds with 28 tasks, 21 executed. It proves the actual engine capture
format, one classified active-owner bucket, the terminal snapshot gauge, and
bench Java compilation.

The final affected-module command was:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-af29 \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-af29 \
  :river-tx:test :river-engine:test :river-bench:test
```

It ran from 2026-09-04 22:47:12.819 BST (epoch `1788558432`) through
22:54:13.104 BST under Gradle build id
`f9707682-17e4-4c38-9de7-525b512a3291`; the retained daemon log is
`/private/tmp/river-gradle-tic-af29/daemon/9.7.0/daemon-58135.out.log`.
The transaction module passed 141 tests. Bench passed 95 tests with two skips.
Engine ran 667 tests with one failure and one skip. The failing existing test,
`SqlSessionTest.namedSavepointCoexistsWithStatementRollback`, expected
`RESOURCE_EXHAUSTED` but received `OK` at line 1122.

That engine failure is not attributed to this ticket. The unchanged candidate
reproduced it as a single-method test, and the exact clean pre-ticket source
`9f756561f79d1ad0952c0ff4d38c07f670badd31` reproduced the identical expected
and actual statuses. The failure is preserved and disclosed; savepoint behavior
was not changed under `tic-af29`.

## Allocation regression, attribution, and correction

An initial affected-module run stopped in the transaction module when the
existing `LockExactAllocationTest` measured 7,080 bytes against its unchanged
512-byte maximum. The ordered discriminator below then measured 6,992 bytes,
while allocation-test-alone passed:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-af29 \
  ./gradlew \
  --project-cache-dir=/private/tmp/river-project-cache-tic-af29 \
  :river-tx:test \
  --tests io.riverdb.tx.LockDeadlockDiagnosticsTest \
  --tests io.riverdb.tx.LockExactAllocationTest
```

A temporary JFR event delimited the measured region. JFR changed the result to
a 152-byte pass and emitted no allocation event in that window, so it provides
no allocation-class or stack attribution. A non-JFR temporary two-window test
then measured 152 bytes in the first consecutive 10,000-operation window and
7,112 in the second. This proves the burst was not only first-use initialization
but still does not identify an allocation class.

The same original ordered test passed at clean pre-ticket source. Temporary
candidate source bisection produced these results, one approved run per point:

| Candidate variant | Result |
| --- | --- |
| remove six capture notifications | pass |
| remove admission/grant trio only | pass |
| remove only `recordActualBlock` call | pass |

The regression was therefore localized to entering the descriptive grant
evaluator while capture was disabled. The production correction guards that
call at admission with the primitive `blockCausality.active()` flag; the callee
keeps its defensive check. The exact original ordered test then passed, followed
by the eight-test focused suite. No threshold, warmup, iteration count, or test
order was changed. Every temporary source patch was reverted with `apply_patch`
and its original SHA-256 restored.

## Slopmark stop-and-review

The pre-edit compact baseline over `river-tx`, `river-engine`, `river-server`,
and `river-bench` production Java reported:

- `EmbeddedDatabase` 164.388;
- `TransactionManager` 159.532;
- `LockExactTable` 87.5097; and
- `TpccServerMain` 75.118.

The first draft moved `LockExactScheduler` to 99.4939, a stop-and-review signal
because the scheduler had gained diagnostic policy. The policy was extracted
to the single-purpose scheduler-owned `LockExactGrantDecision`. The final
compact review reports:

- `EmbeddedDatabase` 164.185;
- `TransactionManager` 155.195;
- `LockExactGrantDecision` 99.4939;
- `LockExactTable` 90.2038;
- `LockBlockCausalitySnapshot` 76.4318;
- `TpccServerMain` 75.118; and
- `LockExactScheduler` outside the top 50.

No TPC-C semantics entered `river-tx`, no grant predicate was duplicated in an
engine/bench layer, and formatting remains in the cold engine boundary.

## Immutable artifact manifest

Every path below is rooted at
`/private/tmp/river-tic-af29-evidence-20260904/allocation`.

| Artifact | SHA-256 | Meaning |
| --- | --- | --- |
| `deadlock-then-allocation.xml` | `63729d707ff63933f5f8568a4da97f4ba418b663437a2b0cf3448d55dca2fcfe` | candidate ordered failure, 6,992 bytes |
| `allocation-alone.xml` | `83b94102eb582650c6252b0ad197823eb20b4b4648821ac3e389e9812c0af8c7` | candidate allocation-only pass |
| `two-window-deadlock-then-allocation.xml` | `4b44ab3c852e1797dbee00e722fa33f108c5cb849cf4ba01a89b51b1bc13e445` | first 152, second 7,112 bytes |
| `baseline-9f756561-deadlock-then-allocation.xml` | `5bb62aa18c58ea1f9ecd37da0add9a20bf7374273449d51a6e6a6ba71a178e72` | exact pre-ticket ordered pass |
| `no-six-hooks-allocation.xml` | `deeb07c4024f1b78062beb37f948a2d90d250c52f1c2944e6d6c248f3f61db25` | six-hook bisection pass |
| `no-six-hooks-deadlock.xml` | `0b4889245d5ba6669884ee9ca80684c959f25c9072dbe04f99be981f286af4e6` | paired deadlock tests pass |
| `no-admission-grant-hooks-allocation.xml` | `0d3d91483dfb28ba54292c51bc86f735c485f8d62b2af5c4a9755a95e1ee68cb` | three-hook split pass |
| `no-admission-grant-hooks-deadlock.xml` | `de13844e066e2cc5abf7d841abbda63e7fd4a7beb81eb229eafde70ed3e6b42f` | paired deadlock tests pass |
| `no-record-actual-block-allocation.xml` | `b79311726d52dd686bb808b98e4667b8bd3789c9ff025d7f9f90b9f1b1fd7dc4` | single-hook split pass |
| `no-record-actual-block-deadlock.xml` | `87a7946f49de3df0c6d57797d3b57fb7df512587995501c06df5282b7d5c3829` | paired deadlock tests pass |
| `call-site-guard-allocation.xml` | `585de9d452b9dc1214c3e1c85989f6065e8ef88304fb82e3c7c89f48c7e69e39` | production-fix allocation pass |
| `call-site-guard-deadlock.xml` | `fad1087ef0f6ed6f1b96332905e38daceee25be59e23b33f899080edca9d4fd2` | production-fix deadlock tests pass |
| `focused-LockBlockCausalityTest.xml` | `bbb57df1ccba66baf594b06a9a10b2c127a8d68525a97cb3c4e0d549bdde9697` | five causal tests pass |
| `focused-LockWaitObservabilityTest.xml` | `58a144d5b315281c65dda6bc36ae16f86d1c7c5314f6bb1d66bb0ede570634ac` | two legacy wait tests pass |
| `focused-LockExactAllocationTest.xml` | `0f4677d80f68829173a3b4392223abf2dd6139a229c944ac6d5803c60a59a487` | final focused allocation pass |
| `wider-post-guard-SqlSessionTest.xml` | `bb5d2d6a1cdac54384947c6eee072cc0ba1a21cd57bc602fd2346ab22689e369` | affected-module engine failure |
| `focused-SqlSessionTest-namedSavepoint.xml` | `7265b9863dce2c6f7250e6d7c80bf9a6ea9c7eb296816bdb96fdc32b54f5e98e` | candidate single-method failure |
| `baseline-9f756561-focused-SqlSessionTest-namedSavepoint.xml` | `92c4d946d7345afc377ffd1b0eb43120b26e8af1587348ca86fda4bcc32bbab4` | identical pre-ticket failure |
| `jfr-instrumented.xml` | `c3986f362a75eac08cf361a807618b2fc93c6342abd0f817b5ccd9e2f7ebf6a5` | perturbed 152-byte pass |
| `profile-31241.jfr` | `dc3af95ade1a3c2a619c646e24716b4e483fb1b53970eb65d189ce74a5909b83` | sandbox wrapper JFR |
| `profile-31280.jfr` | `e299fbe382890c81f05f48ccdbce0081e645f76e694891b7022ad22be501ae8c` | approved wrapper JFR |
| `profile-31293.jfr` | `7976767ea5f0baf3308190b870e58bc71dc7b06590cab696ee45973398fc439b` | test-worker JFR |
| `profile-31293-window.txt` | `63562c880c83a0009ebf491978781750bd3e6ef68c285735364ffe1b1f77cafe` | printed 0/152-byte custom windows |
| `profile-31293-allocations.json` | `7b6b00f2ce9b9fc8479e65d6582b1389b1ee82ebb4e0f8ea4cb681ef9c07e423` | decoded allocation events; none in measured window |

## Remaining gate

After the accepted `tic-0636` runner is merged, use one exact clean source and
its retained build/classpath/host-exclusion evidence for only the required
matched standard-mix diagnostics at two and ten terminals. The result must
classify every measured actual block, reconcile every aggregate and disposition,
and finish with zero retained snapshots, active transactions, held locks, and
queued waiters. The evidence must identify the dominant cause of the prior
10:2 loss; aggregate TPS alone cannot pass this ticket or justify optimization.
