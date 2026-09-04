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
no-secret scans, and reproducible distribution checks. Inspect source and
compiled output for the ADR's deleted plain APIs and nullable branches.

## Acceptance Criteria

Every expected failure has the declared exit/status, emits no false readiness,
leaks no resource or secret, preserves diagnosable data, and leaves the instance
restartable where the contract permits. No plain path remains in source or
compiled output; evidence and source tag are recorded.
