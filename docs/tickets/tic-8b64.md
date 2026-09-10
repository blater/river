---
id: tic-8b64
status: in_progress
type: story
priority: 1
delivery: code
created: 2026-09-10
parent: tic-6d42
deps:
  - tic-c7e2
branch: ticket/tic-8b64-lock-storage
base-commit: ad1db42f5d3d9dcdb6789111a99384041ccaec77
worktree: /private/tmp/river-lock-storage
note: focused and full river-tx correctness tests passed; performance validation, acceptance, tag and merge deferred to parent step 5
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

No performance test has been run for this candidate. Keep the ticket open until
step 5 measures it against the accepted preceding feature, completes integration
checks and either remediates or accepts it for merge/tag/push.
