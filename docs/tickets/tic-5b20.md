---
id: tic-5b20
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify LinuxFileBridge

File: `river-platform/src/main/java/io/riverdb/platform/riverd/linux/LinuxFileBridge.java`. Baseline slopwatch score: **94.471**.

## Approach

Separate typed file operations, namespace operations and native binding setup.
Keep one per-thread errno capture owner, exact architecture syscall numbers,
FFM signatures, arena lifetimes and descriptor ownership. Migrate callers directly
and remove old aliases. Depends on tic-e12b's package-wide native initialization
rule, already included in the integration branch. LinuxRiverDirectory remains
owned by its separate tic-55e0 score ticket.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-platform` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Implementation `7962048e`; Luna/high, Sol/high and lead accepted. FileBridge,
NamespaceBridge and NativeBindings score 0. Existing LinuxRiverDirectory stays
at 137.831 and remains assigned to tic-55e0. Full local platform check passed:
21 executed, 16 non-macOS tests skipped; no failures/errors. JVM workload
installation passed. Log: `/private/tmp/river-tic-5b20-platform-check.log`.
Light sample/all JVM workload on macOS: 4 workers, 1 warehouse, seed 42,
max-retries 20, 5s warmup and 10s measured, version
`tic-5b20-7962048e-jvm`: 302.01 TPS, p99 66.322ms. Zero failed/unknown,
valid invariants and graceful inactive cleanup. Artifact:
`river_harness_20260911_062639_280c7eef` under harness runs.
This checks adjacent behavior, not Linux performance.

Linux CI run 34569955385 reached `:river-platform:test` without a reported
failure but full verification failed in the backup CHECKPOINT test. Stable
master control run 34570183010 reproduced the identical assertion at line 130.
Tracked separately in tic-f737; full Linux verification is not claimed.
The integration already contains tic-e12b's required native initialization rule.
Linux native-image execution remains release-matrix coverage.


Delivered in pushed master integration `4a3b4ef0`, checkpoint
`perf-checkpoint-20260911-score-first46`.
