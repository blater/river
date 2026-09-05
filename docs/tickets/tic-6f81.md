---
id: tic-6f81
status: open
type: investigation
assignee: blater
parent: tic-e5ff
delivery: evidence
tags:
    - performance
    - tpcc
    - p1
    - wal
deps:
    - tic-5b3e
created: 2026-09-04T15:10:07.351684Z
---
# Audit budget-derived chunked WAL for large decisions

Determine exactly whether the current source already streams an admitted large
decision through budget-derived, provider-owned WAL chunks shared by direct and
group commits.

## Design

Trace the direct and group paths through the canonical relational WAL plan,
reservation, encode, append, force, cleanup, and recovery owners. Map every
contract clause to current source, existing tests, and evidence. Record a gap;
do not change code to close it under this ticket.

## Outcome

An exact, evidence-backed current-source contract and gap inventory establishes
whether one admitted logical decision can span budget-derived WAL chunks while
retaining one append/force/publication outcome and exact failure cleanup. If
every clause is satisfied, close this ticket as satisfied. If any clause is not,
name a separately scoped code ticket and update downstream dependencies before
that code work begins.

## In Scope / Owning Mechanism

This investigation owns only the source/test trace and contract matrix for the
existing canonical relational WAL plan, reservation, and append path: chunk
derivation, provider-owned encode storage, ordered streaming, direct/group
equivalence, aggregate byte/record reconciliation, and cleanup across partial
append, force failure, indeterminate outcome, cancellation, and recovery. It
does not modify those owners.

## Non-goals

- A new WAL record or durable format, recovery protocol, commit decision model,
  cohort-admission policy, or coalescing/force tuning.
- Physical mutation compilation, page staging, publication ordering, lock
  policy, or general buffer/resource-plan redesign.
- A parallel batch, reservation, encoder, appender, direct-commit, or group-
  commit path.
- Production, test, fixture, build, benchmark, or diagnostic-tool changes of
  any kind.

## Stop Conditions

- Close as satisfied when every clause is mapped to current source and adequate
  existing evidence.
- When a clause is absent or unproved, record the exact gap and stop. Create a
  separately scoped code or evidence ticket and make downstream work depend on
  it before implementation begins.
- Require a separately reviewed format or durability ticket when the gap would
  change persisted bytes, recovery semantics, or the single commit outcome.
  Reject a throughput-only follow-up because ordinary TPC-C decisions normally
  remain within one WAL record.

## Maximum Change Shape

Documentation and evidence references only. This ticket may update its contract
matrix and cite immutable existing evidence; it may not change production code,
tests, fixtures, build logic, benchmarks, or tools. Any recommended code ticket
must retain one encoding representation, one append path, one force result, and
one publication outcome shared by direct and group commits.

## Acceptance Criteria

- The evidence names the current plan, reservation, encode, append, force,
  publication, cleanup, and recovery owners for both direct and group calls.
- A clause-by-clause matrix identifies source and existing-test evidence, or an
  explicit gap, for chunk boundaries, provider ownership, partial append, force
  failure, indeterminate outcome, cancellation, recovery, and allocation/copy
  behavior.
- WAL bytes and records reconcile to one decision and one force/publication
  outcome, or the inventory records exactly where and why they do not; no
  guessed transaction or record limit is accepted as the contract.
- No production, test, fixture, build, benchmark, or tool file changes under
  this ticket. Every gap names a separately scoped follow-up, and downstream
  dependencies are updated before follow-up implementation begins.

## Notes

### 2026-09-04 ten-terminal architecture priority review

Current source already has chunked relational WAL plans, provider-owned append
storage, and one force outcome for a decision batch. Begin with an exact
remaining-contract inventory and do not add a parallel batch or reservation
path. Ordinary TPC-C writes normally remain within one record, so this ticket
has no independent ten-terminal TPS hypothesis. Accept neutral throughput only
with unchanged copy/allocation counts and proved large-decision, failure, and
recovery behavior.
