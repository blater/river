---
id: tic-e1f7
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverClientConnection

File: `river-client/src/main/java/io/riverdb/client/RiverClientConnection.java`. Baseline slopwatch score: **195.799**.

## Approach

Review `RemoteQuery.prepareMetadata`, `RemoteQuery.close`, `RemoteQuery.next` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-client` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Implementation and validation

Source `3e5c0030` (main refactor `1608ac2f`) keeps transport/exchange on the
connection and gives session state, query lifetime and reusable metadata concrete
owners. No per-row allocation or second transport path. The single session-active
flag replaces duplicate state; metadata generation survives query reuse.

Scores: connection **87.99** (195.799 before), remote query **28.90**, metadata
**22.93**, remote session **5.35**. Luna/high implemented; Sol/high and the lead
approved. Focused connection tests and full client/JDBC checks passed with
`--no-daemon`; the existing query test also checks generation across reuse.

Light performance uses `tools/tps-test.sh`, because the external Go harness does
not exercise this Java client. Configuration: GraalVM25, tiny/standard, four
terminals, one warehouse, seed42, maximum attempts32, 5s warmup, 10s measured;
default isolation/durability/resources unchanged. Installed first-three control
`444d48fb` measured **382.9, 379.7 TPS**; candidate `3e5c0030` measured
**370.2, 475.0 TPS**. All completed with statusOK, zero errors/retries, valid
capture/deadlock accounting and zero active transactions/locks/waiters at cleanup.
No repeated regression; the spread does not support a speedup claim.

Artifacts: `/private/tmp/river-score-20260911/client-tps-control`,
`client-tps-control-2`, `client-tps-candidate-1`, `client-tps-candidate-2` in the
same directory, with adjacent `.log` files. Exact version labels are retained
in each log. Integration publication awaits the subsystem checkpoint.
