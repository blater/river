---
id: tic-telemnar
status: open
type: story
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: code
tags:
    - performance
created: 2026-09-13T11:46:48.638206Z
deps:
  - tic-da4e
---
# Avoid reverse-reference discovery for unchanged referenced keys

### Outcome and admission

Eliminate avoidable discovery before the existing unchanged-key fast path; do not claim to add an unchanged-key check that already exists.

This is a downstream mechanism candidate, not an instruction to implement before
its dependencies deliver affirmative evidence. At handover, the lead records the
exact affected path, measured cost, resource/lifetime contract, and expected
mechanism change in this ticket. If the cost is absent or the mechanism already
exists, record a no-change disposition and revise dependent promotion scope;
never invent work or mark undelivered code as shipped.

### Owner and bounded scope

`RelationalDescriptorReferenceCheck.changed` already avoids probes for unchanged encoded keys. The discovery/enforcement owners decide whether that same validated equality can safely prevent `ForeignKeyChecks.scan` enumeration earlier.

Trace necessary metadata first. Reuse canonical equality and conservative fallback when dependencies cannot be proven unchanged. No duplicate comparison/null policy. This candidate does not depend on the reverse-FK index unless the admitted design demonstrates a real prerequisite and records that edge.

### Acceptance and adversarial tests

Prove unchanged referenced-key updates avoid catalog enumeration, while real referenced-key changes retain enforcement. Cover composite/nullable keys, type/collation representations, self-references, multiple referenced candidate keys, concurrent/private schema changes, savepoint rollback and changed non-key columns. Count discovery and row probes separately; prove statuses/rollback unchanged.

Apply the shared execution/promotion contract in
[the handover](../plans/performance-three-epics-handover.md). The story owns one
coherent merge/rollback boundary with all River-owned callers migrated and the
superseded path removed. Profiled runs prove the mechanism; separate matched
uninstrumented runs establish performance. No per-row allocation, unbounded
retention, extra execution path, weakened isolation/durability, or benchmark-family
policy may be introduced. A negative result is a documented rejection, not a
performance delivery. Independent review is required before promotion.
