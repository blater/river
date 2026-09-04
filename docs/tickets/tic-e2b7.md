---
id: tic-e2b7
status: open
type: epic
assignee: blater
parent: tic-e1c9
delivery: none
tags:
    - riverd
    - security
    - architecture
created: 2026-09-04T15:22:14.615706Z
---
# R0: ratify riverd lifecycle and security contracts

Resolve the proposed plan's remaining security, audit-durability, recovery-command, and deferred-scope ambiguities before launcher implementation.

## Design

Use an accepted ADR for durable public and cross-module choices. Authenticated TLS loopback is mandatory; no development-only plain or unaudited production path is permitted.

## Acceptance Criteria

The ADR and failure matrix name identity, credentials, TLS, authentication, audit durability/admission, lifecycle files, command outcomes, rotation/archive recovery, and performance obligations.
