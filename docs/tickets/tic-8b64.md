---
id: tic-8b64
status: closed
type: story
priority: 1
delivery: code
created: 2026-09-10
parent: tic-6d42
deps:
  - tic-c7e2
branch: ticket/tic-8b64-lock-storage
base-commit: 2a21dbcf4f1ccf76c0425a2adc91dedb974f903c
worktree: /private/tmp/river-lock-storage
note: accepted after clean checks, matched TPS profiles and native crash recovery
---
# Resolve lock storage once without a fixed-depth lookup for every field

## Change

Replace `LockRadixDirectory`'s seven-level traversal on every small-ordinal access
with storage access whose common cost follows the populated working set. Keep
long-addressed, lazily allocated storage and its configured resource budget.
Choose the smallest measured design: a shallow/adaptive directory or existing
segment access resolved once per lock operation. Do not combine competing paths
behind a tuning flag or introduce a process-wide segment cache.

Within an operation, resolve the needed segment/slot once and reuse it for related
field access. Keep lifetime/generation checks at the owning boundary so release,
recycling, cancellation and retry cannot leave stale references. Remove the
superseded traversal implementation and migrate every River-owned caller.

## Acceptance

- Profile the remaining lock lookup cost after insert admission changes. Report
  directory descents and field-access cost through temporary diagnostics plus the
  actual INSERT and TPS workload, not a microbenchmark alone.
- Preserve long/high ordinals, lazy sparse allocation, resource-exhaustion status,
  accounting and reclamation. The closed test work in [tic-855a](tic-855a.md)
  explicitly rejected eager allocation proportional to maximum addressability;
  do not restore it or add a small concurrency cap.
- Exercise acquire/release/recycle, same-key contention, deadlock/cancellation,
  budget exhaustion and reuse with the existing transaction tests. Independent
  concurrency/ownership review verifies reference lifetimes.
- Meet the parent epic's allocation, slopmark, matched workload and checkpoint
  checks. No lock grant/fairness or isolation policy change.

Scheduling follows tic-2e91; there is no technical dependency on its code.

## Candidate ready for step 5

One adaptive radix root replaces the fixed-depth directory. Small segment
ordinals use the root array directly; higher ordinals grow only the necessary
levels. Removing high segments collapses unneeded levels while retaining the
base array. There is no secondary hash table, lookup cache or new lock policy.
The ordinary shallow field lookup is intentionally not cached again.

Independent integrator review checked represented-height bounds, promotion and
collapse, partial allocation failure and preservation of existing entries. Focused
`LockSegmentArenaTest` / `LockExactPressureTest` and full `:river-tx:test` passed
with JDK 25 and `--no-daemon`, in an isolated Gradle home/project cache. Logs:
`/private/tmp/river-tic-8b64-test/focused-escalated.log` and `full.log` alongside it.
Touched `LockRadixDirectory` and `LockLongStore` slopmark scores are 0 → 0;
full before/after artifacts are `/private/tmp/river-tic-8b64-slopmark-baseline-full.txt`
and `/private/tmp/river-tic-8b64-slopmark-after-full.txt`.

## Step 5 acceptance

Rebased onto the accepted unique-lookup checkpoint `2a21dbcf`; measured candidate
`df88f18c`. The preceding accepted samples were 275.600 / 278.033 TPS. Candidate
samples were 451.867 / 452.833, followed by a control recheck of 277.333. All used
four terminals, tiny standard mix, serializable, one warehouse, seed 42, 5-second
warmup and 30-second measurement on GraalVM 25.0.4 JVM, with unchanged durability
and resources. Every run passed invariants with zero retries/errors and successful
capture and cleanup. This is an observed diagnostic improvement, not a general
throughput guarantee.

The matched four-worker INSERT profile improved from 8,722.66 to 9,360.04
inserts/s. `LockRadixDirectory.get` self time fell from 2.318 to 0.256 accumulated
thread-seconds; the lock group fell from 4.519 to 1.904. The final row-count check
passed. Inclusive profile groups overlap and omit unmounted virtual-thread waits.
Artifacts: `/private/tmp/insert-step5/8b64-{candidate-1,candidate-2,control-recheck}`
and `8b64-candidate-profile/`, including commands, versions, raw stacks and SVGs.

Clean full `check` and `:river-bench:installTps` passed with `--no-daemon`:
`/private/tmp/insert-step5/8b64-clean-check.log`. The combined O3/PGO native build
passed (`final-native-build.log`). The actual native executable committed and
verified 100 indexed rows, rejected a duplicate key, survived SIGKILL after
acknowledged commits, recovered all rows and stopped through public `river stop`.
The owned database was removed (`native-crash-smoke.log`). Native throughput was
not measured in this JVM comparison.

Accept the adaptive directory without further caching or lock-policy changes.
Checkpoint: `perf-checkpoint-20260910-lock-storage`.
