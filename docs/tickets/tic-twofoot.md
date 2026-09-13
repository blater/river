---
id: tic-twofoot
status: open
type: story
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: code
tags:
    - performance
created: 2026-09-13T11:46:48.647553Z
deps:
  - tic-da4e
---
# Reset execution scratch in proportion to admitted use

### Outcome and admission

Remove only measured repeated clearing or rebuilding of unused execution scratch, if tic-da4e attributes material cost to it.

This is a downstream mechanism candidate, not an instruction to implement before
its dependencies deliver affirmative evidence. At handover, the lead records the
exact affected path, measured cost, resource/lifetime contract, and expected
mechanism change in this ticket. If the cost is absent or the mechanism already
exists, record a no-change disposition and revise dependent promotion scope;
never invent work or mark undelivered code as shipped.

### Owner and bounded scope

The existing bound-query/session execution workspace owns used extents and reuse. The profile must identify the exact reset carrier before implementation; this is not a new executor or workspace cache.

Use existing capacity and lifetime authorities. Preserve erasure of sensitive payloads at their required boundary; active-prefix reuse cannot leak previous bindings/results after failed or cancelled execution. Retention remains charged to configured budgets.

### Acceptance and adversarial tests

Exercise alternating small/large shapes, success then error, cancellation, prepared-plan invalidation, null/typed values, transaction rollback and session reuse/close. Prove no stale values, pins or sensitive bytes escape, and release under pressure is exact. Measure cleared bytes/slots and allocation per repeated execution; reject absent cost rather than moving clears elsewhere.

Apply the shared execution/promotion contract in
[the handover](../plans/performance-three-epics-handover.md). The story owns one
coherent merge/rollback boundary with all River-owned callers migrated and the
superseded path removed. Profiled runs prove the mechanism; separate matched
uninstrumented runs establish performance. No per-row allocation, unbounded
retention, extra execution path, weakened isolation/durability, or benchmark-family
policy may be introduced. A negative result is a documented rejection, not a
performance delivery. Independent review is required before promotion.
