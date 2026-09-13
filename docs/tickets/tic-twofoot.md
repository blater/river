---
id: tic-twofoot
status: in_progress
type: story
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: code
base-commit: 17fa42f22ad2a4ea9bec2f1721d7acde4c418ffb
branch: ticket/tic-twofoot-result-bitmap
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

### Implementation and evidence, 2026-09-13

The branch was originally claimed at ef935596, then fast-forwarded to published
shutdown checkpoint 17fa42f2 before implementation validation; base-commit records
that updated branch point. Luna implemented the bit/column unit correction;
independent Astra approved source correctness and the measured allocation proof.
The identical regression test fails on the old implementation at 4,800,000 bytes
and passes the candidate's <=256-byte bound over 100,000 warmed command/row
cleanup pairs. All 32 engine-api tests ran without skips or failures. Slopmark
for PublicResultValues remains 12.0027. Source/module policy checks passed.

Clean testClasses and TPS distribution installation passed in six seconds, using
an isolated worktree and Gradle caches. Completed checkpoint batches retain
1,336 test outcomes: 1,320 passed, 16 platform-conditional skips, zero failures.
This is partial full-checkpoint coverage, not a passed full test build. Explicit
engine batches 22 and 31 exceeded the command deadline; the isolated
SqlBlockRowPagedStoreTest.mergesMoreThanSixtyFourConfiguredRunsAndOddTail also
exceeded it. The supervisor stopped each command; process checks found no
remaining Java workers. Aggregate timeouts and unfinished classes remain recorded.
Do not rerun those workloads unchanged or relax the stop to obtain a green gate.

TPS tiny/standard, four terminals, one warehouse, serializable, seed 42,
one-second warmup, server/client heaps 1 GiB/512 MiB:

- Three-second controls: 613.000, 621.333 TPS; candidates: 574.667, 570.667 TPS.
- Five-second interleaved control/candidate/control/candidate:
  626.400 / 614.200 / 599.800 / 615.400 TPS.
- All eight runs completed CHECKPOINT, passed invariants and retry accounting,
  reported zero errors, and left zero active transactions/locks/waiters.

The initial downward shift did not repeat in the longer interleaved samples;
these short diagnostics do not establish a throughput improvement. Only the
PublicResultValues class differs between the installed control/candidate jars.
Retain the measured allocation reduction as the mechanism result. The full
checkpoint remains incomplete, so this ticket stays in progress and the feature
must remain unmerged and untagged pending that gate.

Exact versions, commands, per-family latency buckets, logs and completed test XML:
`/Users/blater/src/river-performance-evidence/20260913-twofoot/`.
See the corresponding entry in [performance checkpoints](../performance-checkpoints.md).
