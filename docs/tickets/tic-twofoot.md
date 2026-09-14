---
id: tic-twofoot
status: closed
type: story
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: code
base-commit: 17fa42f22ad2a4ea9bec2f1721d7acde4c418ffb
branch: ticket/tic-twofoot-result-bitmap
delivered-commit: 2703302760d92059d23f361a9e7df3e8251bc4e6
checkpoint-tag: perf-checkpoint-20260913-result-bitmap
tags:
    - performance
links:
    - tic-da4e
created: 2026-09-13T11:46:48.647553Z
---
# Retain the warmed result bitmap at its word-sized capacity

### Admitted outcome and exact scope

The independent admission review confirms this slice has the required positive
profile and source evidence. tic-da4e remains linked for incomplete broader
wait/transport attribution; those measurements and tic-osgiliath are not
prerequisites for this bitmap correction. This changes readiness, not the
implementation or promotion evidence required below.

Fresh tic-da4e evidence attributes repeated result scratch rebuilding to
PublicResultValues.releaseHighWater. Independent Astra review confirms a unit
mismatch: nulls.capacity() is in bits rounded to whole 64-bit words, while
lanesToKeep counts retained columns (8/16). Every ordinary cleanup releases and
reallocates the same long[1]. Compare retained bitmap capacity in matching units.

This replaces the original broad reset hypothesis. Existing bound-query active
extent resets already work and are not changed. Only PublicResultValues and
focused public result tests belong in this slice. Do not change SessionEndpoint
release policy, row/query publication timing, budgets, TLS or other allocations.

### Acceptance and review

Keep reset/erasure semantics, retain zero bitmap capacity for zero lanes, and
release genuinely larger bitmaps when shedding high water. Cover warmed repeated
command/row releaseHighWater allocation, wide-to-small reuse, null/typed-value
staleness, invalid input then reuse, exact lease accounting and final release to
zero. Existing reset-only allocation coverage is not sufficient for this path.

Luna/high implements; independent Astra checks the unit conversion, ownership,
allocation proof and scope before integration. Use focused engine-api tests,
affected-module checks and the required accepted clean checkpoint. Matched
TPS controls/candidates must show no unexplained regression; the primary
mechanism claim is removal of the witnessed repeated bitmap allocation, not a
promised throughput multiplier. Record the profile/correctness result and promote
immediately with commit/tag/merge/push when accepted.

### Implementation and final validation, 2026-09-13

Originally claimed at ef935596; the branch was fast-forwarded to published
17fa42f2 before validation, as recorded by base-commit. Luna implemented the
bit/column unit correction. Independent Astra approved source correctness and
its allocation proof: the old implementation fails at 4,800,000 bytes over
100,000 warmed command/row cleanup pairs; the candidate passes the unchanged
<=256-byte bound. All 32 engine-api tests executed without skips. Slopmark for
PublicResultValues remains 12.0027; source/module policy checks passed.

Clean testClasses and installTps completed in six seconds with isolated Gradle
caches and the build cache enabled. The default clean JVM test checkpoint was
completed through sequential Gradle batches and direct JUnit execution: 1,942
passed, 18 expected skips (16 platform-conditional and two opt-in wider TPC-C
lifecycle tests). This does not claim one aggregate clean-check command passed.
Every test-bearing source class is accounted for by completed XML or JUnit logs.

An existing graph-sort allocation test failed at 808 bytes, then 576 bytes in a
matched-order check (limit 512). The failures remain in the evidence. A prescribed
control/candidate pair with identical classpaths passed; complete class-load logs
prove neither JVM loaded PublicResultValues or its public result owners. The
changed code did not execute in that measurement. Independent review attributes
neither failure to this bitmap change; the exact allocation source remains
unexplained test variability. No assertion or allocation limit was weakened.

### Correct progress deadline and long-test completion

The initial absolute 15-second command budget was an incorrect interpretation
of the user's requirement. The deadline measures lack of meaningful progress,
not elapsed runtime; AGENTS.md now makes this distinction explicit.

Temporary, independently reviewed instrumentation binds the exact test thread
and reports only successful finite work completions. An unrelated thread,
observer heartbeat or busy CPU cannot extend the external deadline. Timestamped
progress prevents old buffered messages from reviving a stalled operation;
escalation remains latched, without blocking cleanup. A healthy fixture passed
in 17.116 seconds. The watchdog failure-mode test also passed: its deliberately
stalled owner, active unrelated worker and deliberately stuck shutdown hook were
stopped in 14.047 seconds with no surviving Java process. This was an injected
test condition, not an observed River hang or a test failure.

The unchanged 65-run merge passed in 32.188 seconds (643,785 completed work units).
The unchanged 65,537-row sort/join/checkpoint/reopen test passed in 60.652 seconds
(1,802,342 units). Both JVMs exited normally. Instrumentation was restricted to
these two correctness runs; allocation and TPS evidence remains uninstrumented.
No remote CI run or deadline exception was needed.

### Matched TPS evidence and promotion

Tiny/standard, four terminals, one warehouse, serializable, seed 42, one-second
warmup, server/client heaps 1 GiB/512 MiB, JFR off:

- Three-second controls: 613.000, 621.333 TPS; candidates: 574.667, 570.667 TPS.
- Five-second interleaved control/candidate/control/candidate:
  626.400 / 614.200 / 599.800 / 615.400 TPS.
- All eight runs completed CHECKPOINT, passed invariants and retry accounting,
  reported zero errors, and left zero active transactions/locks/waiters.

The initial downward shift did not repeat in the longer interleaved samples;
these short diagnostics establish no throughput improvement. Only
PublicResultValues.class differs between the installed control/candidate jars.
The mechanism benefit is the measured removal of repeated bitmap allocation.

Exact versions, commands, per-family latency buckets, failed attempts, completed
XML, direct JUnit logs and the progress monitor are retained at
`/Users/blater/src/river-performance-evidence/20260913-twofoot/`.
See FOLLOWUP.md there and [performance checkpoints](../performance-checkpoints.md).

Independent Astra promotion review reconciled the completed class/method coverage
and 1,942 passing tests plus 18 expected skips, and approved this allocation
reduction for integration.


### Publication completed, 2026-09-14

Atomic push published integration 27033027, ticket/tic-twofoot-result-bitmap
and perf-checkpoint-20260913-result-bitmap to origin. The prior publication
block is resolved; the accepted allocation correction is closed.
