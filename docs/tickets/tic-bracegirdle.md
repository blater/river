---
id: tic-bracegirdle
status: closed
type: story
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: evidence
evidence:
    - docs/tickets/tic-bracegirdle.md
tags:
    - performance
deps:
    - tic-da4e
created: 2026-09-13T11:46:48.629924Z
---
# Replace repeated reverse foreign-key catalog discovery

### Outcome and admission

Avoid repeated catalog-wide reference enumeration for parent UPDATE/DELETE/drop when fresh evidence shows material discovery cost.

This is a downstream mechanism candidate, not an instruction to implement before
its dependencies deliver affirmative evidence. At handover, the lead records the
exact affected path, measured cost, resource/lifetime contract, and expected
mechanism change in this ticket. If the cost is absent or the mechanism already
exists, record a no-change disposition and revise dependent promotion scope;
never invent work or mark undelivered code as shipped.

### Owner and bounded scope

`RelationalDescriptorForeignKeyChecks.scan` and the existing catalog/schema dependency owner own discovery. Relational enforcement and reference equality stay with their current owners.

Maintain or derive bounded reverse dependencies with the authoritative schema identity and DDL transaction. Before coding choose eager publication or generation-bound derivation using the actual catalog contract. Do not create a second independently invalidated catalog. Keep ownership for incoming references distinct from row-probe semantics.

### Acceptance and adversarial tests

Compare lookup results with authoritative catalog discovery for zero/one/multiple incoming references, composite keys, self-references and cycles. Cover create/drop/rename, private and concurrent DDL, DDL rollback/savepoints, crash/recovery/reopen, pressure and cleanup. Fresh profile must show fewer catalog enumerations per affected mutation without missing constraints or increasing steady-state row allocation.

Apply the shared execution/promotion contract in
[the handover](../plans/performance-three-epics-handover.md). The story owns one
coherent merge/rollback boundary with all River-owned callers migrated and the
superseded path removed. Profiled runs prove the mechanism; separate matched
uninstrumented runs establish performance. No per-row allocation, unbounded
retention, extra execution path, weakened isolation/durability, or benchmark-family
policy may be introduced. A negative result is a documented rejection, not a
performance delivery. Independent review is required before promotion.

### Reviewed no-code rejection, 2026-09-14

Rejected for this epic; no reverse catalogue is implemented. Fresh accepted-
source profiling observes FK scan in 53/1,578 inclusive selected request/commit
stacks (52 UPDATE, one DELETE). This is sampled presence, not avoidable CPU-time
or a predicted throughput gain. The smaller tic-telemnar discovery avoidance
passed its mechanism tests but did not establish repeatable useful workload
benefit in eight matched short/long samples. That does not prove every reverse
index useless; it does not justify the larger publication, lifetime and DDL
invalidation responsibility under this bounded epic.

Independent execution_admission_review recommends this no-code rejection using
the current profile and telemnar outcome. Existing authoritative catalogue and
reference enforcement remain the owners. No new cache, schema representation,
follow-up workstream or production change is introduced. See tic-da4e for the
profile limits and tic-telemnar for individual samples and immutable report IDs.
