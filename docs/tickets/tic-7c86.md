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

## Review notes

`RiverDaemonCredentials` remains the public material and lifecycle owner.
Manifest encoding/parsing, certificate invariants, generation storage/load
state, client publication, and bounded credential file access have concrete
package-private owners. File handles close before directory status is returned;
cleanup failures retain the primary failure and every acquired resource is
attempted. Credential-equivalent byte arrays are wiped after use, and client
publication forces the security directory only after stage close. Draft
slopwatch scores for all changed Java files are below 90; focused build and
test results remain pending the shared build slot. The shared file owner is
named `RiverDaemonCredentialFiles`; a focused regression verifies that a stage
close failure is returned and prevents the parent security force.
