---
id: tic-5f87
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify WindowsFileBridge

File: `river-platform/src/main/java/io/riverdb/platform/riverd/ntfs/WindowsFileBridge.java`. Baseline slopwatch score: **102.192**.

## Approach

Build on `tic-e334`. Give Windows native bindings, status capture and call-state
storage one owner, and move the existing ACL/security-descriptor policy into a
concrete security owner. Keep actual file operations in WindowsFileBridge.
Preserve typed signatures, capture options, the single status state, UTF-16 and
security-descriptor lifetimes, and all caller ordering. Remove old forwarding
paths together; add no per-call allocation. The package-wide runtime-init rule
from `tic-e12b` covers new native owners.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-platform` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `dc4c1c87` (initial split `4476b6da`) on
`ticket/tic-5f87-windows-bridge`, with `tic-e334` dependency. WindowsFileBridge
falls from 102.192 to 83.1434; WindowsNativeBindings scores 0 and
WindowsSecurityPolicy 31.1548. Root and Sol checked every typed ABI signature,
call-state capture option, shared status lifetime, token/descriptor/arena cleanup,
ACL predicate and caller ordering. Review caught a deleted path-encoding helper;
the exact existing helper was restored before validation. The duplicate success
constant was removed and the rights-mask comment restored.

Platform checks and TPS installation passed with `--no-daemon`: 23 tests passed,
16 Linux/Windows tests skipped on macOS, no failures or errors. Log:
`/private/tmp/river-tic-5f87-platform-check.log`. Windows execution remains
unvalidated on this host; the existing package-wide runtime-init rule covers
new native owners, but local compilation is not native Windows runtime evidence.

Light JVM sample all, four workers, one warehouse, seed 42, 20 retries,
5-second warmup/10-second measurement: **268.58 TPS**, p99 **63.603 ms**,
446 retries, zero failed/unknown outcomes, passed invariants and graceful inactive
cleanup. This is consistent with the current short-run variation; the preceding
Windows slice's longer control/candidate investigation did not retain the
short-run slowdown. No speedup is claimed.
Version `tic-5f87-dc4c1c87-jvm`; artifact
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_070929_ddf8f1bf`.

Delivered in `perf-checkpoint-20260911-score-first51`.
