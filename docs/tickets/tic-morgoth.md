---
id: tic-morgoth
status: open
type: story
priority: 1
assignee: blater
parent: tic-primula
delivery: code
tags:
    - performance
created: 2026-09-13T11:46:48.680882Z
deps:
  - tic-edoras
---
# Pipeline admitted requests through the existing ordered transport

### Outcome and admission

Submit the design-approved independent request sequence and coalesce its writes without waiting unnecessarily for each prior reply.

This is a downstream mechanism candidate, not an instruction to implement before
its dependencies deliver affirmative evidence. At handover, the lead records the
exact affected path, measured cost, resource/lifetime contract, and expected
mechanism change in this ticket. If the cost is absent or the mechanism already
exists, record a no-change disposition and revise dependent promotion scope;
never invent work or mark undelivered code as shipped.

### Owner and bounded scope

The existing client exchange/transport owner, server session dispatcher and Java/Go adapters own one atomic protocol/consumer delivery. No second executor, connection pool, retry loop or result encoder.

Keep actual SQL execution order and dependency barriers. One admitted buffer/queue ownership model bounds pending requests, response bytes and streaming results; use configured resource limits with explicit pressure outcomes. If framing changes, migrate all owned consumers and fixtures in the same delivery. Keep one canonical path and delete superseded exchange behavior.

### Acceptance and adversarial tests

Prove response association and result equivalence with deliberately fragmented/coalesced frames, partial writes, earlier statement errors, midstream cancellation, large streaming results, pressure and slow readers. Cover autocommit/explicit transactions/savepoints, one-way releases followed by barriers, transport loss before/after durable commit, no replay of uncertain outcomes, and payload erasure only after ownership ends. Show syscall/wait movement plus repeatable workload benefit; count reductions alone fail acceptance.

Apply the shared execution/promotion contract in
[the handover](../plans/performance-three-epics-handover.md). The story owns one
coherent merge/rollback boundary with all River-owned callers migrated and the
superseded path removed. Profiled runs prove the mechanism; separate matched
uninstrumented runs establish performance. No per-row allocation, unbounded
retention, extra execution path, weakened isolation/durability, or benchmark-family
policy may be introduced. A negative result is a documented rejection, not a
performance delivery. Independent review is required before promotion.
