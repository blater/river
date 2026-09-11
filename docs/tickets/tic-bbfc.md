---
id: tic-bbfc
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonStop

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonStop.java`. Baseline slopwatch score: **341.692**.

## Approach

Review `RequestState.run`, `RiverDaemonStop.scan`, `Control.validateForServer` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Delivery evidence

Stop request/join, completion proof, server control, shared directory mutation and shared
record reading now have concrete owners. River-owned callers use them directly; the stop
request codec remains the format owner. The source scan leaves every original and extracted
file below 90 (maximum **71.3904**). `:river-server-app:compileJava` passed, the focused
runtime, stop, identity, request and client lifecycle tests passed, and
`:river-server-app:check :river-bench:installTps` passed with isolated Gradle state.
