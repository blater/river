---
id: tic-b901
status: open
type: story
priority: 1
assignee: blater
parent: tic-2109
delivery: code
tags:
    - riverd
    - security
    - audit
    - recovery
deps:
    - tic-72ea
    - tic-ec50
created: 2026-09-04T15:23:11.631905Z
---
# Implement offline audit archive and credential renewal

Implement the ADR's explicit stopped-instance recovery operations under exclusive ownership.

## Design

Validate and content-identify immutable audit archives without overwrite, preserve corrupt audit, create and force a new active audit, and renew a complete credential generation while preserving prior public identity as specified.

## Acceptance Criteria

Live-owner rejection, archive collision, corruption preservation, full-at-start, runtime exhaustion, forced publication, renewal interruption, stale client config, and restart tests pass with no silent truncation, repair, or secret exposure.
