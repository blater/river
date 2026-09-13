---
id: tic-gwindor
status: open
type: story
priority: 1
assignee: blater
parent: tic-primula
delivery: code
tags:
    - performance
created: 2026-09-13T11:46:48.671624Z
deps:
  - tic-edoras
---
# Retain prepared handles across repeated client executions

### Outcome and admission

Reduce measured client prepare/release churn for repeated statements on the same live connection, only where the existing client does not already retain them.

This is a downstream mechanism candidate, not an instruction to implement before
its dependencies deliver affirmative evidence. At handover, the lead records the
exact affected path, measured cost, resource/lifetime contract, and expected
mechanism change in this ticket. If the cost is absent or the mechanism already
exists, record a no-change disposition and revise dependent promotion scope;
never invent work or mark undelivered code as shipped.

### Owner and bounded scope

Existing Java client/JDBC prepared-handle ownership and the independently owned Go adapter. Server shared-plan ownership from tic-7a32 and one-way release from tic-6f28 remain unchanged.

Use a named repeated execution consumer from tic-edoras. Keep parameter values/results execution-local; preserve connection and transaction ownership, schema invalidation and server restart/reconnect semantics. Retained handles are bounded by existing resource admission and released exactly once. No new server plan cache; no benchmark semantics in the client.

### Acceptance and adversarial tests

Test changing parameter/null types, early client close, release ordering, invalidation/reprepare after DDL, handle exhaustion/eviction, connection loss and restart, independent handles sharing a plan, and cancellation. Compare Java/Go results and resource release. Show lower prepares/releases per attempt and reduced end-to-end cost, with no unbounded server retention.

Apply the shared execution/promotion contract in
[the handover](../plans/performance-three-epics-handover.md). The story owns one
coherent merge/rollback boundary with all River-owned callers migrated and the
superseded path removed. Profiled runs prove the mechanism; separate matched
uninstrumented runs establish performance. No per-row allocation, unbounded
retention, extra execution path, weakened isolation/durability, or benchmark-family
policy may be introduced. A negative result is a documented rejection, not a
performance delivery. Independent review is required before promotion.
