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

Resolve the proposed plan's remaining security, recovery-command, and deferred-scope ambiguities before launcher implementation. SQL/security audit is deferred.

## Design

Use an accepted ADR for durable public and cross-module choices. Authenticated TLS loopback, authorization, database/WAL durability, and recovery are mandatory. SQL/security audit is not part of the current launcher contract.

## Acceptance Criteria

The ADR and failure matrix name identity, credentials, TLS, authentication,
authorization, database/WAL durability, lifecycle files, command outcomes,
credential renewal recovery, and performance obligations.
