---
id: tic-72ea
status: open
type: story
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - riverd
    - security
    - audit
    - wal
deps:
    - tic-11a5
created: 2026-09-04T15:23:11.086718Z
---
# Implement resource-accounted durable security audit

Replace per-operation synchronous audit forcing with the mechanism accepted in
the `tic-a221` evidence merged at
`e592addff67ac6016ae6e9e37e3bf374a6511f0d`, while retaining fail-closed
semantics.

## Design

Keep audit policy in one `river-server` security owner; implement the exact
event, byte-reservation, group-force, exhaustion, archive-control, and recovery
state machines ratified by ADR 0014. Change every `SessionAuthorizer` caller
together; no transitional authorization wrapper remains.

## Acceptance Criteria

Authentication and statement admission, group force, crash, corruption, exhaustion, cancellation, archive, allocation, and secret-erasure tests pass; matched authenticated TPS shows the removed force mechanism without an unexplained regression.
