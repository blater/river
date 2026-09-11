---
id: tic-e12b
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify DarwinFileBridge

File: `river-platform/src/main/java/io/riverdb/platform/riverd/apfs/DarwinFileBridge.java`. Baseline slopwatch score: **120.888**.

## Approach

Separate typed file descriptor operations from directory enumeration, metadata
and namespace operations. Give ABI handle initialization and errno capture one
shared binding owner. Migrate callers directly without forwarding aliases; retain
typed invokeExact calls, original native signatures, pointer lifetimes and
immediate errno capture. Do not add per-call allocation or generic dispatch.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-platform` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Implementation `bdfd2390`, comment-only correction `453b51b5`; Luna/high,
Sol/high and lead accepted. DarwinFileBridge and NativeBindings score 0;
NamespaceBridge 16.7807. All touched files remain below 90.
Full platform check passed (21 executed, 16 Linux/Windows cases skipped on macOS),
including all 11 APFS cases. All 41 focused lifecycle/client tests and server-app
checks/installTps passed. Logs: `/private/tmp/river-tic-e12b-platform-check.log`,
`...-focused-tests.log`, `...-focused-check-final.log`.
Light sample/all JVM workload: 4 workers, 1 warehouse, seed 42, max-retries 20,
5s warmup and 10s measured. Initial candidate 266.45 TPS prompted adjacent
control/candidate runs: 306.76/306.30 TPS, p99 62.816/62.980ms. Versions:
`score-first33-e12b-adjacent`, `tic-e12b-bdfd2390-adjacent`; artifacts
`river_harness_20260911_061418_b450f44e` and
`river_harness_20260911_061505_fb158561` under harness runs.
Both passed with zero failed/unknown, valid invariants and graceful inactive
cleanup. The initial slowdown did not repeat; no speedup claim. Native
compilation and actual executable smoke are required at the integration checkpoint.

The integrated O3/PGO native build passed in 1m46s. The actual executable passed
start, ps identification, generated credentials, create/insert/select and endpoint
stop with graceful cleanup. Evidence: `integration-first42-native.log` and
`integration-first42-native-smoke.log` under the campaign evidence directory.
