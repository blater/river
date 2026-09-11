---
id: tic-486d
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify NioDurableDirectory

File: `river-platform/src/main/java/io/riverdb/platform/file/nio/NioDurableDirectory.java`. Baseline slopwatch score: **91.131**.

## Approach

Give NIO directory listing one owner for its stream, type scan and exception
translation. Keep reset, admission and synchronization in NioDurableDirectory.
Preserve NOFOLLOW attributes, fatal corruption fencing, result generation/finish,
and close/error precedence. Do not alter namespace durability, rename, force,
handle generations or the existing filename contract.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-platform` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `bdde7493` on `ticket/tic-486d-nio-directory`.
NioDurableDirectory falls from 91.131 to 77.2063; NioDirectoryListing scores 0.
Root and Sol reviewed reset/admission under the existing monitor, NOFOLLOW
attributes, fatal corruption, generation publication and stream-close/error
precedence. Namespace durability and handle behavior are unchanged.

Platform checks and TPS installation passed with `--no-daemon`: 23 executed,
16 platform-specific skips on macOS, zero failures/errors. Log:
`/private/tmp/river-tic-486d-platform-check.log`.

Light JVM sample all, four workers, one warehouse, seed 42, 20 retries,
five seconds warmup and ten seconds measured: **286.58 TPS**, p99 **67.502 ms**,
471 retries, zero failed/unknown outcomes, passed invariants and graceful inactive
cleanup. No regression observed or speedup claimed from this short diagnostic.
Version `tic-486d-bdde7493-jvm`; artifact
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_072041_34e9646b`.
