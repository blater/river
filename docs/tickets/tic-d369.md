---
id: tic-d369
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify SqlSessionExecutionCoordinator

File: `river-engine/src/main/java/io/riverdb/engine/sql/SqlSessionExecutionCoordinator.java`. Baseline slopwatch score: **285.853**.

## Approach

Keep one session execution gate. Move prepared-plan admission and binding into a cohesive existing or local owner, and isolate scan completion from command dispatch. Preserve reusable execution workspaces, authorization, transaction boundaries and cleanup; do not add an allocation or a second executor on the per-row path.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Delivery evidence

The coordinator retains the ordered session execution gate and direct point/DML
dispatch. Statement preparation owns parser/binding preparation; prepared
validation owns retained-plan checks; scan lifecycle owns scan execution and
completion. There is no second executor or new per-execution allocation.
Luna/high implemented; Sol/high independently reviewed the final ownership and
status ordering; the lead reviewed the cross-owner lifetime contract.

Scores: coordinator **88.467**, preparation **70.701**, validation **44.852**,
scan lifecycle **37.967**, down from 285.853 for the original coordinator.
Focused session, lease, retained-prepared counter and allocation tests passed,
as did JVM distribution installation. An earlier allocation assertion reported
1,576 bytes; subsequent unchanged candidate reruns and the adjacent unchanged
control passed. The cause of that isolated failure is not established; no
assertion was relaxed.

Version `tic-d369-1f197a4c-jvm`, source `1f197a4c`, used the epic's fixed JVM
sample/all workload (four workers, seed 42, one warehouse, 20 retries, 5s warmup,
10s measurement): **256.86 TPS**, p99 **65.700ms**, 445 retries, zero failed or
unknown outcomes, passing invariants and graceful stop to inactive. This is
within the 240.07–294.15 TPS control range; no speed improvement is claimed.

Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_021613_96d2fada`.

Accepted implementation and evidence were merged and pushed at `444d48fb`,
checkpoint `perf-checkpoint-20260911-score-first3`.
