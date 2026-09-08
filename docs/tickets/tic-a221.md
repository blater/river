---
id: tic-a221
status: closed
type: investigation
assignee: blater
parent: tic-e2b7
delivery: evidence
base-commit: 0f7916153eeca3d3062f10c6588c7c4d6fb66bf8
branch: ticket/tic-a221-audit-durability
delivered-commit: e592addff67ac6016ae6e9e37e3bf374a6511f0d
evidence:
    - docs/delivery/evidence/2026-09-04-tic-a221-audit-durability-design.md
tags:
    - riverd
    - security
    - audit
    - performance
created: 2026-09-04T15:23:10.903963Z
---
# Define scalable security-audit durability and admission

Resolve the durability contract that previously forced every admitted operation synchronously and collapsed TPS.

This closed investigation is retained as historical evidence. Its design and
acceptance criteria below are superseded and do not create an active audit
implementation or performance prerequisite.

## Design

Specify which events must be durable before admission, how group append/force or another mechanism preserves that guarantee, crash/recovery semantics, bounded byte-budget admission, exhaustion/backpressure, archive interaction, and disabled/non-applicable cost.

## Acceptance Criteria

Concurrency, security, recovery, and performance reviewers accept the state machine; every event is accounted for; no secret is retained; no arbitrary record cap or unaudited fallback is introduced; a matched baseline and regression test are defined.

## 2026-09-08 supersession

This closed investigation remains historical evidence only. Its audit design,
acceptance criteria, and measurements are superseded as an active `riverd`
requirement. SQL/security audit is deferred with no archive command or
placeholder implementation. Reconsideration requires a concrete architecture
that supports neutral TPS, latency, and resource impact before any new study or
implementation is scheduled.
