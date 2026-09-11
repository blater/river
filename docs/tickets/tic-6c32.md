---
id: tic-6c32
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify TransactionManager

File: `river-tx/src/main/java/io/riverdb/tx/TransactionManager.java`. Baseline slopwatch score: **160.555**.

## Approach

Consolidate group member validation and failure terminalization in the existing
TransactionCompletion owner. Keep every public synchronized boundary on the
manager, all validation before mutation, exact allowed-state and duplicate
contracts, result-reset timing and failure classification. Remove private
forwarding-only layers; retain one explicit two-state admission helper.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-tx` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Implementation `275054ed`; Luna/high, Sol/high and lead accepted. Manager
36.5532 (from 160.555), existing Completion 84.9177. No new allocation or monitor.
Focused manager/group/program checks: 68 tests, all clean. Full transaction module:
149 tests, no failures/errors/skips. Engine checks/installTps passed. Logs:
`/private/tmp/river-tic-6c32-build.log` and `...-tx-fullcheck.log`.
Light external sample/all,4 workers,1 warehouse,seed 42,max-retries 20,5s/10s:
initial 287.83 TPS/p99 81.723ms is excluded because a second transaction test build
overlapped that sample. Adjacent frozen first33 control 301.40 TPS/p99 63.930ms
and candidate 319.59 TPS/p99 58.720ms ran without overlapping builds. Versions:
`score-first33-6c32-adjacent`, `tic-6c32-275054ed-adjacent`; artifacts
`river_harness_20260911_055031_30cf2785` and
`river_harness_20260911_055121_9fe3c2fa` under harness runs.
Both passed with zero failed/unknown, valid invariants and graceful inactive
cleanup. No observed regression; no speedup claim from these short diagnostics.
