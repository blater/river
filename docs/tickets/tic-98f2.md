---
id: tic-98f2
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify ProtocolResponseEncoder

File: `river-protocol/src/main/java/io/riverdb/protocol/ProtocolResponseEncoder.java`. Baseline slopwatch score: **216.949**.

## Approach

Review `ProtocolResponseEncoder.encodeQueryOpen`, `ProtocolResponseEncoder.encodeRow`, `ProtocolResponseEncoder.encodePrepared` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-protocol` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Delivery evidence

Response APIs retain operation field/flag mapping. The shared frame writer owns
fixed layout; query-open encoding owns the metadata/first-row contract; metadata
validation and serialization share one owner. Existing value, frame-wire and
segment owners remain authoritative. No new allocation or wire-format change.
Luna/high implemented; Sol/high and the lead approved byte layout, status ordering,
fragmentation, budgets and ownership.

Scores: response encoder **29.274**, query-open encoder **74.079**, metadata and
frame writers **0**, down from 216.949. Protocol check, client/server tests and
JVM distribution installation passed with isolated caches and no Gradle daemon.

Version `tic-98f2-deb1998f-jvm`, source `deb1998f`, used the epic's fixed light
workload: **255.29 TPS**, p99 **71.631ms**, 470 retries, zero failed/unknown
outcomes, passing invariants, graceful stop and inactive final service state.
This is within the 240.07–294.15 TPS control range; no speed claim is made.

Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_021823_47de4675`.
