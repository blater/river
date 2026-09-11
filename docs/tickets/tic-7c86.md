---
id: tic-7c86
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonCredentials

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonCredentials.java`. Baseline slopwatch score: **385.589**.

## Approach

Review `RiverDaemonCredentials.load`, `RiverDaemonCredentials.persist`, `RiverDaemonCredentials.parseManifest` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Delivery evidence

The public material/lifecycle API delegates to concrete certificate, manifest,
storage/load, client-publication and shared file-I/O owners. No duplicate write
loop remains. Close failures preserve the first status; scratch secret bytes are
wiped. Publication closes its stage before the parent-directory force. A focused
fault regression proves stage-close failure is returned and suppresses that
force; its injected failure still closes the real descriptor.

Luna/high authored and Sol/high plus the lead reviewed the final code. Source
`14a77c4a` passed all 66 server-app tests (no skips/failures/errors), module check
and JVM distribution installation. Scores: original facade **7.427** (from
385.589), certificate **66.428**, client publication **42.107**, manifest
**24.854**, shared files **20**, load state **15.688**, storage **12.893**,
new fault test **0**.

The epic's fixed light JVM workload, version `tic-7c86-14a77c4a-jvm`, passed at
**258.82 TPS**, p99 **67.633ms**, 471 retries. An adjacent unchanged control
`score-control-444d48fb-adjacent-7c86` reproduced **258.89 TPS**, p99 **66.093ms**.
Both passed invariants with zero failed/unknown outcomes and graceful cleanup.
The lower throughput than earlier short samples reproduced on unchanged code;
there is no observed regression in the adjacent pair. Candidate startup 1.552s,
shutdown 0.892s.

Artifacts under `/Users/blater/src/ingres/river-harness/runs/`:

- Candidate: `river_harness_20260911_025024_12113f34`.
- Adjacent control: `river_harness_20260911_025615_7d0d10af`.
