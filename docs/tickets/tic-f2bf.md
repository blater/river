---
id: tic-f2bf
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify TpccPromotionGates

File: `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccPromotionGates.java`. Baseline slopwatch score: **130.145**.

## Approach

Keep general outcome accounting and family coverage in TpccPromotionGates.
Give the existing Alpha3 mix/sample qualification rules one concrete policy
owner, with their expected proportions. Preserve diagnostic order, thresholds,
category bypasses, overflow checks and report validity decisions exactly.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-bench` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `1e8175fd`, validation merge `e44011a6`, on
`ticket/tic-f2bf-promotion-gates`. TpccPromotionGates falls from 130.145 to
30.5752; TpccAlpha3PromotionPolicy scores 6.31517. Root and Sol reviewed
exact diagnostic ordering, both overflow checks, family coverage, sample/mix
rules, tolerance arithmetic, category bypasses and output. No policy was weakened.

Eight focused promotion, metrics and artifact tests passed with zero failures;
bench checks, source-policy validation and TPS installation passed in 11 seconds
with `--no-daemon`. Log: `/private/tmp/river-tic-f2bf-gradle.log`.

The actual Java TPS consumer ran tiny/standard, four terminals, one warehouse,
seed 42, maximum attempts 32, five seconds warmup and ten seconds measured.
Version `tic-f2bf-1e8175fd-jvm`: **462.400 TPS**, zero retries/errors,
valid checkpoint/recovery and accounting, valid capture, zero cutoff transactions
and zero remaining transactions/locks/waits. Consistent with adjacent 468.500
and 472.800 samples; no speedup claim. Evidence:
`/private/tmp/river-score-20260911/tic-f2bf-candidate` and adjacent `.log`.
