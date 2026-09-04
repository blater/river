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

Implement exact `riverd audit archive -D` and `riverd credentials renew -D`
stopped-instance operations under exclusive ownership.

## Design

Implement the accepted five-step audit control transition without overwrite,
preserve corrupt audit, and refuse archive for terminal `EXHAUSTED` authority.
Renew a complete generation without overlap, preserving and forcing the prior
public certificate and manifest before publishing the new security authority.

## Acceptance Criteria

Live-owner rejection, archive collision, corruption preservation, full-at-start, runtime exhaustion, forced publication, renewal interruption, stale client config, and restart tests pass with no silent truncation, repair, or secret exposure.
