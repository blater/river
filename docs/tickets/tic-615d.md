---
id: tic-615d
status: open
type: story
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - riverd
    - security
    - identity
    - filesystem
deps:
    - tic-11a5
created: 2026-09-04T15:23:11.178601Z
---
# Implement incarnation-bound instance credentials and identity

Implement launcher-owned atomic instance metadata and owner-only credential bundle persistence for first creation and strict restart.

## Design

Bind the database incarnation, credential generation, certificate digest, algorithms, principal, permissions, validity, and client configuration. Validate regular-file, symlink, ownership, mode, size, hostname, and manifest invariants before listener start.

## Acceptance Criteria

Partial first publication is recoverable only before authority exists; accepted missing or mismatched material fails closed; no implicit regeneration or arbitrary non-empty-directory adoption occurs; fault and permission tests pass.
