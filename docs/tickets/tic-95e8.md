---
id: tic-95e8
status: open
type: investigation
assignee: blater
parent: tic-bf0b
delivery: evidence
tags:
    - riverd
    - security
    - distribution
    - evidence
deps:
    - tic-ec50
created: 2026-09-04T15:23:11.364586Z
---
# Prove fail-closed riverd distribution lifecycle

Exercise the exact installed revision through the real lifecycle and security failure matrix before operational extensions.

## Design

Use fresh instance directories and the real `riverd-client-v1` configuration;
include wrong token/certificate/hostname, replay, corrupt files, unsupported
filesystem proof, occupied port, engine-open failure, startup interruption,
broken stdout before/after ready-file publication, stdout prefix/partial/final
record races without a ready file, under-lock torn/stale pre-bootstrap lock and
bound stale-ready recovery, no-secret scans, and reproducible
distribution checks. Inspect source and
compiled output for the ADR's deleted plain APIs and nullable branches.

## Acceptance Criteria

Every expected failure has the declared exit/status, emits no false readiness,
and every completely observed readiness commit follows its declared
post-commit outcome. Post-visibility ready force and ambiguous stdout-only
failure terminate as `IO_FAILURE`; ready-file stdout-mirror failure remains
nonterminal. No failure leaks a resource or secret; diagnosable data is
preserved and the instance is restartable where the contract permits. No plain
path remains in source or compiled output. Record the exact
runtime-observable distribution/JDK/provider/OS/ext4-or-xfs tuple separately
from the evidence-only mkfs/device/mount/barrier/ACL-mask/fault-harness tuple.
Create the immutable installed
`lib/riverd-filesystem-qualification-v1.properties` record and bind
`docs/delivery/evidence/tic-95e8-riverd-filesystem-api.json` to its checksum and
the complete launcher-JAR checksum. Cover SDS-returned public `FileChannel`
force/tryLock, API, race, injected-crash, process-crash, same-parent stage/alias,
ACL, and every enumerated path operation; explicitly defer power-loss promotion
to `tic-9640`. Evidence and source tag are recorded.
