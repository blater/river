---
id: tic-55e0
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify LinuxRiverDirectory

File: `river-platform/src/main/java/io/riverdb/platform/riverd/linux/LinuxRiverDirectory.java`. Baseline slopwatch score: **137.831**.

## Approach

Give the Linux directory listing operation one owner for its stream and decoding storage. Share the existing POSIX filename policy between Linux and APFS, with the additional Windows restrictions explicit in the same validator. Preserve native status capture, descriptor lifetime, and namespace publication ordering.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-platform` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation: `5426002e`, branch `ticket/tic-55e0-linux-directory`, with the
reviewed Linux bridge dependency `tic-5b20`. LinuxRiverDirectory falls from
137.831 to 27.0842; LinuxDirectoryListing scores 34.5066 and RiverDirectoryNames
20.5552. The listing owns its stream and buffer; filename rules have one shared
owner with explicit platform differences. Constant-only flag methods are gone.
Root and Sol reviewed errno capture, close precedence, admission and publication
ordering, filename equivalence, and the absence of new runtime allocation.

`:river-platform:check :river-bench:installTps` passed with `--no-daemon`:
23 tests passed, 16 platform-specific tests skipped on macOS, no failures or
errors. Log: `/private/tmp/river-tic-55e0-platform-check.log`.
Linux runtime coverage retains the baseline CI limitation recorded in `tic-f737`;
this local run does not claim Linux or Windows runtime validation.

Light JVM sample: `sample all`, four workers, one warehouse, seed 42,
20 retries, 5-second warmup and 10-second measurement, version
`tic-55e0-5426002e-jvm`: **303.85 TPS**, p99 **63.701 ms**, 488 retries,
zero failed or unknown outcomes, all invariants passed, graceful shutdown and
inactive owned service. This is within the adjacent samples' observed variation.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_064507_80d16b4f`.
An initial sandboxed launch failed before readiness and produced no workload
sample; the authorized local-server run above completed successfully.


Delivered in pushed master integration `4a3b4ef0`, checkpoint
`perf-checkpoint-20260911-score-first46`.


The first-51 clean integration run exposed a source-policy violation in the new
NUL filename test literal. Commit `44e3e5f6` replaces its raw Unicode escape with
the equivalent Java octal NUL escape. The resulting bytecode is unchanged; full
integration checks pass after the correction.
