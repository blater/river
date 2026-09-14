---
id: tic-5b3e
status: in_progress
type: story
priority: 1
assignee: blater
parent: tic-rowlie
delivery: code
base-commit: 39eb104cb83dc8f20b838d4bc8d9173433f00fb5
branch: ticket/tic-5b3e-cohort-admission
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
# Admit resource-bounded cohort prefixes through canonical staging

Form cohorts by cumulative admitted page, version, staging, and WAL demand rather than fixed transaction or record counts.

### Design

Use one cumulative admission policy for direct and group commits. Logical write,
version and WAL demand is already sealed and leased per session before enqueue;
the cohort consumes those receipts without reserving them twice. The canonical
compiler admits each distinct changed page incrementally, before staging it,
against the configured staging-page lease and the remaining structural
`currentFrames` generation capacity. This per-page count admission does not
guarantee that a physical frame will be available at freeze time: pin state and
eviction I/O remain owned by the page cache and are resolved there. No page
forecast or duplicate traversal is added.

If a member cannot grow the ordered prefix because a cumulative budget is
temporarily full, roll back only that unaccepted member before WAL append or
publication, complete the accepted prefix through the existing writer, then
return the deferred suffix to the head of the queue in arrival order. If the
first member cannot fit the configured resource boundary by itself, terminalize
that member with the existing RESOURCE_EXHAUSTED or RETRY status and then
resume its suffix. Direct commits use the same policy with a one-member prefix.

### Outcome

One cumulative admission decision selects the largest safe cohort prefix, or
returns an explicit pressure/impossible-request outcome before WAL append or
publication, using all admitted page, version, staging, and WAL demand. Reversible
member staging may be rolled back to preserve the accepted prefix. Every
reservation is held and released exactly once with the transaction outcome.

### In Scope / Owning Mechanism

The commit coordinator owns one cumulative cohort-admission policy shared by
direct and group commits. It consumes sealed per-transaction demand from
`tic-ca05`, consults the existing authoritative budget owners, selects or splits
one ordered prefix, and passes that prefix to the one canonical physical writer.
Budget owners retain responsibility for their units and capacity; this ticket
owns only their cumulative cohort decision and reservation lifecycle.

The per-page staging check uses the existing IndexedPageState changed-page
admission and IndexedPreparedPageBatch generation owner. A failed member drops
only its partial staged pages, pending generation slots, operation row/heap
frontier and version-operation suffix; its frozen prefix remains intact,
including when freeze fails partway through pages or a page already changed by
an earlier member. Only recognized page-capacity pressure may split/defer a
suffix. I/O, corruption and invariant failures retain their existing terminal
failure handling. The session resource lease remains held through its eventual
outcome, so any staged-page high-water already admitted remains reserved while
the suffix waits.
The durable row-ID owner is queried for available capacity so only the selected
prefix reaches its mutating admission call; a whole-cohort miss must not latch
maintenance when a prefix fits. WAL sizing receipts remain session-owned and
LocalWal validates the selected prefix's exact record/address range before
append. Provider page memory remains reserved once at database open.

### Non-goals

- Separate admission algorithms, queues, or resource policies for individual
  page, version, staging, or WAL budgets.
- Retuning budget sizes or coalescing delays, redesigning the compiled database
  resource plan, or introducing fixed transaction/record/cohort caps.
- Logical sizing or preparation, page-demand forecast/duplicate traversal, WAL
  representation or append format, durable publication, lock policy, or a
  second transaction outcome.

### Stop Conditions

- Stop before coding if the complete pre-implementation contract above cannot
  be stated using the existing budget authorities. Create a named design
  dependency for the unresolved semantic decision rather than absorbing it
  into this implementation ticket.
- Stop and split out unrelated work if a discovered budget defect can be fixed
  independently of cumulative cohort admission.
- Reject an implementation that silently underfills or fails a whole cohort
  where an admitted ordered prefix could proceed, cannot cancel backpressure,
  or cannot reconcile every reservation and release.

### Maximum Change Shape

One coordinator-owned cumulative admission policy, one cumulative demand
carrier, and one reservation lifecycle shared by direct and group commits may
change, plus their focused tests and counters. Existing budget owners may be
called but not duplicated. No second writer, executor, queue, WAL path,
transaction outcome, or per-budget admission framework may be introduced.

### Acceptance Criteria

Concurrent admission, partial-cohort failure, cancellation, overflow, cleanup, and direct/group equivalence tests pass; counters reconcile cumulative demand without arbitrary caps.

### Notes

### 2026-09-04 ten-terminal architecture priority review

This is a scalability and failure-safety prerequisite, not a direct fix for the
current ten-terminal singleton cohorts. Current source aggregates only part of
the cohort demand. The completed mechanism must report admitted prefix size,
every budget that split or rejected a cohort, cancellable backpressure, and
head-of-line effects. A configured budget becoming the next limit after
durability overlap is acceptable; silent underfilled cohorts or whole-cohort
failure are not.

### 2026-09-13 performance realignment

Moved from `tic-e5ff` to `tic-rowlie`. Existing dependencies and unfulfilled correctness gates remain authoritative. Follow [the current handover](../plans/performance-three-epics-handover.md); this move certifies no implementation or performance outcome.

### Current implementation boundary, 2026-09-14

ca05 is closed at evidence commit 2333ab60, integrated at 8bc05e3c. P0 is
explicitly deferred and is not this ticket's blocker. Status remains open:
the source audit found that the required pre-implementation contract cannot yet
be satisfied within this ticket's physical-staging non-goal.

| Budget or lifetime | Existing source authority | Remaining boundary |
| --- | --- | --- |
| Logical write entries, versions and WAL bytes | IndexedPreparedLogicalCommit.prepare; IndexedTransactionResourceAdmission; DatabaseResourceGovernor | Already leased per transaction before enqueue and included in database totals. A second cohort receipt must not double-charge them. |
| Retained session workspaces | DatabaseRetainedLease through ensureRetainedDatabaseAccountedBytes | Remain charged until session close; transaction leases end at terminal cleanup. |
| Version operation workspace and durable row IDs | IndexedPreparedCommitCohortDemand; IndexedHybridCommitGroup.preflight; IndexedDurableVersionAdmission | Existing cohort sum covers versions. Impossible demand returns RESOURCE_EXHAUSTED; reclaimable durable-version pressure returns RETRY. |
| Staging pages | IndexedPageFrameCache.stageExisting/stageNew and IndexedPageState.addChangedPage | Existing configured per-operation checks precede page mutation, but session staged-page demand is only grown after compileCumulative discovers it. |
| Frozen publication generations | IndexedPreparedPageBatch.freeze | Each changed member generation consumes a current-frame slot and pins its predecessor. The cumulative capacity is checked after staging; there is no sealed pre-stage demand or prefix-preserving member rollback. |

The concrete existing test
IndexedGroupCommitFaultTest.preflightFailureAbortsPreparedMembersWithoutWalOrFallbackAndAllowsNextCommit
proves one member fits but the pair fails and both abort under a constrained
page-cache plan. This is safe whole-cohort failure, not the required admitted
prefix behavior. It passed in the 43-test ca05 validation at the same production
source. No additional benchmark or new reproducer is necessary to establish it.

A configured capacity is a legitimate structural bound. The blocker is not
missing exactness alone: a proved safe demand bound could suffice, but none
currently supports the ticket's prefix/no-underfill requirement. Reserving the
whole pool or only summing existing logical receipts does not establish it.

Implementing the full contract requires a decision in physical demand planning
or staging/rollback ownership, both excluded by the current ticket. The Luna/high
worker and independent admission reviewer found no existing route that satisfies
the complete contract without that change. This blocker is superseded by the
user-directed amendment below; the earlier evidence remains as the reason for
the amended scope.

### 2026-09-14 user-directed physical-admission amendment

The user authorized extending the existing page-staging owner with exact
per-page pre-mutation admission and member-local rollback. This resolves the
earlier stop condition without adding a forecast, second traversal, or new page
budget owner. The work remains on the existing canonical writer and preserves a
safe prefix; suffix requests resume at the queue head only after the prefix
completes. Structural `currentFrames` admission does not promise that an
unpinned physical slot will be available at freeze time; a member-local rollback
handles that pressure before WAL append. Only recognized page-capacity pressure
may defer a suffix. I/O, corruption and invariant failures retain terminal
failure handling. The P0 scaling regression and warmup accounting gap remain
deferred and are not prerequisites for this delivery.
