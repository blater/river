---
id: tic-5b3e
status: closed
priority: 1
type: story
assignee: blater
parent: tic-rowlie
delivery: code
delivered-commit: 9d14de924a2ad2b9e7d29035ac4df531a9b502d0
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
# Admit cumulative cohort prefixes before page mutation

Form cohorts by cumulative admitted page, version, staging, and WAL demand rather than fixed transaction or record counts.

### Design

Use the existing logical resource leases, then admit each member's physical
pages immediately before mutation. Publish the largest safe ordered prefix and
roll back only the pressure-rejected member while retaining one canonical
physical writer and one transaction outcome.

### Outcome

One cumulative admission decision selects the largest safe cohort prefix using
the existing logical write, version and WAL leases plus exact per-page physical
admission. A pressure-rejected member is restored before WAL append, and its
suffix resumes at the queue head after the admitted prefix completes. Every
reservation remains owned and released exactly once with its transaction
outcome.

### In Scope / Owning Mechanism

The commit coordinator owns one cumulative cohort-admission policy shared by
direct and group commits. It consumes sealed logical demand from `tic-ca05`,
uses each existing budget owner, and passes the safe prefix to the one canonical
physical writer. `IndexedPageFrameCache` remains the physical-page owner and
admits each page before mutation; the group preflight owns member-local rollback.
Existing session leases remain the only receipts for logical writes, versions,
staging capacity and WAL bytes, so cohort selection does not double-charge them.

### Non-goals

- Separate admission algorithms, queues, or resource policies for individual
  page, version, staging, or WAL budgets.
- Retuning budget sizes or coalescing delays, redesigning the compiled database
  resource plan, or introducing fixed transaction/record/cohort caps.
- A precomputed physical-page forecast, second compilation traversal, new page
  budget owner, WAL representation change, lock-policy change, or second
  transaction outcome.

### Stop Conditions

- Stop and split out unrelated work if a discovered budget defect can be fixed
  independently of cumulative cohort admission.
- Reject an implementation that silently underfills or fails a whole cohort
  where an admitted ordered prefix could proceed, cannot cancel backpressure,
  or cannot reconcile every reservation and release.

### Maximum Change Shape

One coordinator-owned cumulative admission policy, one cumulative demand
carrier, per-page pre-mutation admission in the existing page owner, and
member-local rollback may change, plus their focused tests and counters.
Existing leases and budget owners may be called but not duplicated. No second
writer, executor, queue, WAL path, transaction outcome, physical forecast, or
per-budget admission framework may be introduced.

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

### Historical implementation boundary, superseded 2026-09-14

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

The current implementation reuses the reviewed `79c4da2e` engine/test delta on
the latest checkpoint. Its historical checkpoint crash is tracked separately;
validation here uses the no-checkpoint TPS path with write diagnostics disabled.

### 2026-09-15 validation

The reused mechanism passed the six focused affected test classes, including
direct/group equivalence under both fitting and deterministic pinned-page
pressure, prefix publication under pressure, member-local rollback, terminal
force failure after a one-member prefix, and crash/reopen recovery. The terminal
case leaves no active transaction, lock, waiter or visible suffix and restores
the pre-cohort durable tail, commit sequence and row count.

Matched no-checkpoint TPS controls at `bbbd3803` were **846.8 / 784.8 TPS**;
candidate samples were **887.9 / 866.5 TPS**. All four used GraalVM 25.0.4,
tiny standard mix, four terminals, one warehouse, serializable isolation,
no-wait stress scheduling, seed 42, 2-second warmup and 10-second measurement.
They passed invariants, reconciliation and cleanup with zero errors; control 2
and candidate 2 each had one correlated deadlock retry. Persisted-write
diagnostics were disabled and CHECKPOINT was skipped. These short samples show
no repeated regression and support acceptance of the correctness mechanism;
they are not a qualified throughput claim. Raw evidence is retained at
`/private/tmp/river-tic-5b3e-evidence`.

The final clean `clean check --continue` gate passed all repository checks in
3m 3s, and independent correctness review found no remaining blocker after the
direct/group pressure and terminal-prefix regressions were added. Delivered
feature commit: `9d14de924a2ad2b9e7d29035ac4df531a9b502d0`.
