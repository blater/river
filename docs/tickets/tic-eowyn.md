---
id: tic-eowyn
status: open
type: story
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: code
tags:
    - performance
created: 2026-09-13T11:46:48.620348Z
deps:
  - tic-da4e
---
# Reuse derived table binding metadata by schema identity

### Outcome and admission

Remove repeated descriptor-to-binding-view column/index metadata construction shown by tic-da4e, without changing transaction binding admission.

This is a downstream mechanism candidate, not an instruction to implement before
its dependencies deliver affirmative evidence. At handover, the lead records the
exact affected path, measured cost, resource/lifetime contract, and expected
mechanism change in this ticket. If the cost is absent or the mechanism already
exists, record a no-change disposition and revise dependent promotion scope;
never invent work or mark undelivered code as shipped.

### Owner and bounded scope

`SqlBindingTableResolver`, `RelationalDescriptorJoinTableView` and the existing binding/schema owners from tic-5c21 own this change. Reuse the existing owner; do not layer another cache over it.

Separate immutable descriptor-derived shape from transaction-owned pins and mutable execution state. Key reuse by complete schema/descriptor identity and generation, including private DDL visibility. Specify invalidation and configured memory admission before coding; authorization is checked at its existing boundary, not cached away.

### Acceptance and adversarial tests

Prove warmed repeated resolves stop rebuilding/copying column and index metadata. Test independent pin release, commit/rollback, savepoint rollback, private DDL, concurrent rename/drop/recreate, index/constraint change, authorization changes where applicable, schema pressure and session close. An old view must not survive a new descriptor identity. Check retained bytes and construction/allocation on the real repeated path.

Apply the shared execution/promotion contract in
[the handover](../plans/performance-three-epics-handover.md). The story owns one
coherent merge/rollback boundary with all River-owned callers migrated and the
superseded path removed. Profiled runs prove the mechanism; separate matched
uninstrumented runs establish performance. No per-row allocation, unbounded
retention, extra execution path, weakened isolation/durability, or benchmark-family
policy may be introduced. A negative result is a documented rejection, not a
performance delivery. Independent review is required before promotion.
