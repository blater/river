---
id: tic-2de1
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonIdentityRecords

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonIdentityRecords.java`. Baseline slopwatch score: **113.231**.

## Approach

Give checksum encoding and validated envelope decoding one concrete owner.
Preserve explicit padded identity/stop framing and exact runtime/ready framing,
including their different checksum-marker searches. Migrate all existing callers
and delete duplicate checksum bodies. Parse datadir once with the same predicates.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Implementation `5aa26d0c`; Luna/high, Sol/high and lead accepted. IdentityRecords
and RuntimeCodec now 0, shared RecordEnvelope 5.68752, framing tests 0.
37 focused tests and server-app checks/installTps passed; log:
`/private/tmp/river-tic-2de1-focused-check.log`. Tests include valid controls
for padded identity acceptance and runtime rejection of padding/embedded markers.
Light sample/all JVM workload,4 workers,1 warehouse,seed 42,max-retries 20,
5s warmup/10s measured, version `tic-2de1-5aa26d0c-jvm`:295.64 TPS,
p99 66.519ms,468 retries; zero failed/unknown, valid invariants, graceful inactive
cleanup. Artifact:`river_harness_20260911_055610_48c31216` under harness runs.
No observed regression; no speedup claim.
