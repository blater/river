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

Replace per-operation synchronous audit forcing with the accepted durable admission mechanism while retaining fail-closed semantics.

## Design

Keep audit policy in one security owner; batch or coalesce only where the ADR proves equivalent ordering; account retained bytes against configured resources; return explicit pressure before statement side effects.

## Acceptance Criteria

Authentication and statement admission, group force, crash, corruption, exhaustion, cancellation, archive, allocation, and secret-erasure tests pass; matched authenticated TPS shows the removed force mechanism without an unexplained regression.
