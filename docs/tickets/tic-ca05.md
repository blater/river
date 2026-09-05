---
id: tic-ca05
status: open
type: investigation
assignee: blater
parent: tic-e5ff
delivery: evidence
tags:
    - performance
    - tpcc
    - p1
    - transactions
    - admission
deps:
    - tic-1dda
created: 2026-09-04T15:10:07.168483Z
---
# Audit pre-queue logical commit sealing and admission

Determine exactly whether the current source already seals and admits one
immutable logical commit before enqueue without moving physical work ahead of
the canonical writer.

## Design

Trace the direct and group call paths from session-owned logical preparation to
commit-queue admission and terminal cleanup. Map every contract clause to its
production owner, state transition, existing test, and current evidence. Record
a gap; do not change code to close it under this ticket.

## Outcome

An exact, evidence-backed current-source contract and gap inventory establishes
whether one immutable logical commit and its admitted demand are prepared before
queue entry and remain stable through publication or abort. If every clause is
satisfied, close this ticket as satisfied. If any clause is not, name a
separately scoped code ticket and update downstream dependencies before that
code work begins.

## In Scope / Owning Mechanism

This investigation owns only the source/test trace and contract matrix for the
existing session-owned logical-preparation and admission path: logical mutation
sealing, result retention, long-valued demand sizing, authenticated resource
receipts, direct/group equivalence, and exact-once cleanup. It must identify the
canonical physical writer that alone turns the description into pages, versions,
or WAL; it does not modify either owner.

## Non-goals

- Commit-sequence assignment, physical compilation, page staging or freezing,
  final conflict validation, WAL append or force, publication, and lock policy.
- Cohort formation, coalescing policy, durability overlap, or general runtime
  resource-budget redesign.
- A new prepared representation, executor, sizing pass, queue, or worker.
- Production, test, fixture, build, benchmark, or diagnostic-tool changes of
  any kind.

## Stop Conditions

- Close as satisfied when every clause is mapped to current source and adequate
  existing evidence.
- When a clause is absent or unproved, record the exact gap and stop. Create a
  separately scoped code or evidence ticket and make downstream work depend on
  it before implementation begins.
- Reject a proposed follow-up that moves physical work before queue entry,
  creates an independently prepared page image or another representation/
  executor, or adds an earlier scan without reducing canonical-writer work.

## Maximum Change Shape

Documentation and evidence references only. This ticket may update its contract
matrix and cite immutable existing evidence; it may not change production code,
tests, fixtures, build logic, benchmarks, or tools. Any recommended code ticket
must preserve one logical description, one authoritative sizing pass, one set
of receipts, and one canonical physical writer.

## Acceptance Criteria

- The evidence names the current logical-preparation owner, immutable carrier,
  queue transition, canonical physical writer, receipt owner, and cleanup owner
  for both direct and group calls.
- A clause-by-clause matrix identifies source and existing-test evidence, or an
  explicit gap, for success, cancellation, impossible request, transient
  pressure, retry, publication, abort, and cleanup.
- The inventory proves that admitted demand remains stable or records exactly
  where and why it does not; logical and physical work are clearly separated.
- No production, test, fixture, build, benchmark, or tool file changes under
  this ticket. Every gap names a separately scoped follow-up, and downstream
  dependencies are updated before follow-up implementation begins.

## Notes

### 2026-09-04 ten-terminal architecture priority review

Current source already invokes session-owned logical preparation before queue
admission. Start this ticket with an exact source-gap inventory and treat it as
hardening of that owner, not permission to add another prepared representation,
executor, or sizing pass. Report logical-preparation time together with the
remaining physical preflight time. Reject a change that repeatedly scans New
Order work while locks are retained without reducing the canonical writer's
measured service cost. This is a correctness and scalability enabler; it has no
independent throughput promise.
