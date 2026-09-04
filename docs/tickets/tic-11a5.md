---
id: tic-11a5
status: in_progress
type: story
assignee: blater
parent: tic-e2b7
delivery: documentation
base-commit: 8df484694039e9b53cd7ff6c5cccb44973b86c0e
branch: ticket/tic-11a5-ratify-riverd-security
evidence:
    - docs/adr/0014-riverd-instance-security.md
    - docs/plans/riverd-standalone-server-plan.md
tags:
    - riverd
    - security
    - adr
deps:
    - tic-de1d
    - tic-a221
created: 2026-09-04T15:23:10.997919Z
---
# Ratify the riverd instance security and command ADR

Turn the proposed launcher plan into one accepted public lifecycle and security contract before production wiring.

## Design

Resolve exact command/status outcomes, TLS and token identity, filesystem proof, audit durability, archive and certificate-expiry recovery commands, readiness/client configuration, stop fencing, and the contradictory deferred wording in the plan.

## Acceptance Criteria

The accepted ADR and updated plan have no alternative behaviors, name owners and failure statuses, include the audit performance contract, and pass architecture, security, operations, and compatibility review.
