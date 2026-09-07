---
id: tic-e1c9
status: open
type: epic
assignee: blater
delivery: none
tags:
    - riverd
    - server
    - security
    - lifecycle
created: 2026-09-04T15:22:14.525134Z
---
# Deliver the supported standalone riverd lifecycle

Provide one installed authenticated River server command and make it the required lifecycle boundary for external stress and comparison consumers.

## Design

docs/plans/riverd-standalone-server-plan.md is the authority. river-server remains a reusable transport adapter; river-server-app is the composition root; no source-tree classpath or insecure fallback is retained.

## Acceptance Criteria

The child contract, distribution, operations, and consumer-migration epics close with real lifecycle, security, recovery, build-policy, and external-consumer evidence.

## 2026-09-07 scope reconciliation

This is the third user-selected outcome, scheduled after commit architecture
and measured overlap. It remains technically independent and retains the
existing security, distribution, operations and consumer-migration children.

The canonical outcome mapping is in [tic-e5ff](tic-e5ff.md).
