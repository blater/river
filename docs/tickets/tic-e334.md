---
id: tic-e334
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify WindowsRiverDirectory

File: `river-platform/src/main/java/io/riverdb/platform/riverd/ntfs/WindowsRiverDirectory.java`. Baseline slopwatch score: **139.081**.

## Approach

Build on `tic-55e0`'s shared filename validator. Give Windows directory listing
one owner for its fresh descriptor-relative stream, cursor, confined buffer and
entry decoding. Preserve Windows reparse-point rejection, status and close
behavior; do not import POSIX semantics. Reassess the remaining score before
any further split.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-platform` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `a4a7a7db` on `ticket/tic-e334-windows-directory` depends on
`tic-55e0`. WindowsRiverDirectory falls from 139.081 to 25.461;
WindowsDirectoryListing scores 27.3697, WindowsDirectoryInspection 0, and the
updated filesystem caller 12.8523. Root and Sol independently checked the exact
native inspection/security predicates, fresh enumeration cursor, restart and
entry decoding, result/status ordering, and handle/arena lifetimes. No additional
runtime allocation or dispatch was introduced. Package-wide native runtime
initialization is already provided by the `tic-e12b` dependency.

`:river-platform:check :river-bench:installTps` passed with `--no-daemon`:
23 tests passed, 11 Linux and 5 Windows tests skipped on macOS, zero failures or
errors. Log: `/private/tmp/river-tic-e334-platform-check.log`. Windows native
execution is not validated by this macOS run; the source-equivalence review and
local integration results do not claim Windows runtime coverage.

All performance samples used JVM, sample all, four workers, one warehouse,
seed 42 and 20 maximum retries. Initial 5-second warmup/10-second samples were
280.58 and 270.41 TPS versus the adjacent stable control's 309.49 TPS. That
repeated difference triggered longer testing with agent work paused. With
10-second warmup/30-second measurement, the candidate produced **399.34 TPS**
(p99 44.466 ms), versus stable control **364.02 TPS** (p99 48.202 ms).
The short-run slowdown did not persist; accept the structural refactor without
claiming a performance improvement. All samples had zero failed/unknown outcomes,
passed invariants, graceful shutdown and inactive owned services.

Artifacts under `/Users/blater/src/ingres/river-harness/runs/`:

- `river_harness_20260911_065224_dc3600c8`: initial candidate, version `tic-e334-a4a7a7db-jvm`.
- `river_harness_20260911_065330_2146eda1`: adjacent control, version `score-first42-e334-adjacent`.
- `river_harness_20260911_065457_76a0eb7e`: adjacent candidate, version `tic-e334-a4a7a7db-adjacent`.
- `river_harness_20260911_065611_797cf0da`: longer candidate, version `tic-e334-a4a7a7db-longer`.
- `river_harness_20260911_065709_2df88196`: longer control, version `score-first42-e334-longer`.


Delivered in pushed master integration `4a3b4ef0`, checkpoint
`perf-checkpoint-20260911-score-first46`.
