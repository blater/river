---
id: tic-5b3e
status: open
type: story
assignee: blater
parent: tic-e5ff
delivery: code
tags:
    - performance
    - tpcc
    - p1
    - transactions
    - admission
deps:
    - tic-ca05
created: 2026-09-04T15:10:07.259189Z
---
# Reserve cumulative cohort demand before physical staging

Form cohorts by cumulative admitted page, version, staging, and WAL demand rather than fixed transaction or record counts.

## Design

Reserve against compiled runtime budgets before side effects. Apply cancellable backpressure or split admission while retaining one canonical physical writer and one transaction outcome.

## Outcome

One cumulative admission decision selects the largest safe cohort prefix, or
returns an explicit pre-side-effect pressure/impossible-request outcome, using
all admitted page, version, staging, and WAL demand. Every reservation is held
and released exactly once with the transaction outcome.

## In Scope / Owning Mechanism

The commit coordinator owns one cumulative cohort-admission policy shared by
direct and group commits. It consumes sealed per-transaction demand from
`tic-ca05`, consults the existing authoritative budget owners, selects or splits
one ordered prefix, and passes that prefix to the one canonical physical writer.
Budget owners retain responsibility for their units and capacity; this ticket
owns only their cumulative cohort decision and reservation lifecycle.

Before production work, record for every budget its unit, demand source,
reservation point and lifetime, impossible-versus-transient status, prefix or
backpressure rule, cancellation behavior, and exact release owner. No
implementation begins while any of those semantics are unresolved.

## Non-goals

- Separate admission algorithms, queues, or resource policies for individual
  page, version, staging, or WAL budgets.
- Retuning budget sizes or coalescing delays, redesigning the compiled database
  resource plan, or introducing fixed transaction/record/cohort caps.
- Logical preparation, physical staging, WAL representation or append, durable
  publication, lock policy, or a second transaction outcome.

## Stop Conditions

- Stop before coding if the complete pre-implementation contract above cannot
  be stated using the existing budget authorities. Create a named design
  dependency for the unresolved semantic decision rather than absorbing it
  into this implementation ticket.
- Stop and split out unrelated work if a discovered budget defect can be fixed
  independently of cumulative cohort admission.
- Reject an implementation that silently underfills or fails a whole cohort
  where an admitted ordered prefix could proceed, cannot cancel backpressure,
  or cannot reconcile every reservation and release.

## Maximum Change Shape

One coordinator-owned cumulative admission policy, one cumulative demand
carrier, and one reservation lifecycle shared by direct and group commits may
change, plus their focused tests and counters. Existing budget owners may be
called but not duplicated. No second writer, executor, queue, WAL path,
transaction outcome, or per-budget admission framework may be introduced.

## Acceptance Criteria

Concurrent admission, partial-cohort failure, cancellation, overflow, cleanup, and direct/group equivalence tests pass; counters reconcile cumulative demand without arbitrary caps.

## Notes

### 2026-09-04 ten-terminal architecture priority review

This is a scalability and failure-safety prerequisite, not a direct fix for the
current ten-terminal singleton cohorts. Current source aggregates only part of
the cohort demand. The completed mechanism must report admitted prefix size,
every budget that split or rejected a cohort, cancellable backpressure, and
head-of-line effects. A configured budget becoming the next limit after
durability overlap is acceptable; silent underfilled cohorts or whole-cohort
failure are not.
