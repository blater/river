# tic-b368: current commit durability and the next ownership boundary

Date: 2026-09-07. Source: `f8a39fda2e5ba1a54b261f9993fb2be8a2daf55b`;
`2a5e3ed` adds outcome mapping only. No production probes, benchmarks or code
changes accompany this evidence. ADR 0007 remains the frontier authority.

## Decision and delivery split

The first outcome is `tic-7352`: capture an exact WAL force target before I/O,
complete/release that same identity, and retain the corresponding engine cohort
identity through acknowledgement and cleanup. Its current synchronous caller
immediately exercises the replacement. Scheduling remains serial and there is
no standalone speedup claim. It does not remove every single-cohort constraint.

The second is `tic-f1bb`: overlap canonical physical preparation/append/publication
of eligible queued work with an earlier local force. It must atomically replace
all remaining single-pending-cohort assumptions, including page-generation pins,
operation scratch, pending ranges and cumulative retained demand. Class extraction
or simply moving force onto another thread is insufficient.

The third remains `tic-e1c9`, with its existing security, distribution, operations
and consumer contracts. It is scheduled afterward and has no technical dependency
on the first two outcomes. No cross-database performance claim is admitted before
its existing lifecycle certification gate.

## Current source and evidence

| Fact | Canonical source | Existing evidence |
| --- | --- | --- |
| Logical preparation precedes enqueue; physical preparation belongs to the writer | `IndexedGroupCommitCoordinator.commit/run`, `IndexedGroupCommitBatch.appendSharedGroup` | ca05 remains an exact admission audit; f539 traces actual queue delay |
| Group publication and lock handoff already precede force | `IndexedGroupCommitBatch.process/publishPrepared/completeDurability`, `TransactionManager.publishCommitGroup`, `TransactionCompletion.publishCommittedGroup` | `IndexedGroupCommitFaultTest.publishedGroupHandsOffLocksButRetainsAdmissionAndWithholdsReaderCompletion` |
| Published transactions still consume admission until completion | `TransactionManager.activeTransactionCount`, `TransactionCompletion.publishedPending` | same held-force test; existing maximum-active-transactions authority |
| Dependent result delivery waits; unrelated durable observations may finish | `IndexedTable.awaitDurability`, session observation tracking from e544 | e544 held-force row/absence/tombstone/index/write/savepoint tests, cancellation and fencing |
| The writer cannot process another cohort while force blocks | `IndexedGroupCommitCoordinator.run` calls `batch.process` synchronously | f539: 41.1/41.4% writes overlap force in queue; mean 1.554/1.506 ms for affected writes |
| WAL force completion samples mutable append state after I/O | `LocalWalForceCoordinator.force`, `LocalWal.markForced` | current single-owner ordering makes this work; it does not authorize concurrent append |
| Release and forced cursor use one aggregate pending range | `LocalWal.releaseForcedBatchInternal/openForcedCursor`, `DurableWalQuorum` | existing local/quorum/logical-stream tests; suffix retention is unproved |
| One installed page batch retains publication pins until force | `IndexedPreparedPageBatch.begin/install/release`, `IndexedHybridCommitGroup.cleanupPreparedWork` | prepared-publication member-CSN/recovery tests and held-force tests |
| Operation phase and scratch remain live until durability completes | `IndexedHybridCommitGroup.begin/completeDurability`, `IndexedStorePhase`, kernel operation versions | current direct/group/fault tests; a second physical cohort is not supported |
| The latest corruption stop is resolved | f8dd two directory loaders and read eviction | six red/green regressions, clean 1,781-test checkpoint, repeated successful 30/60s workloads |

The f539 window-fit model is optimistic scheduling evidence, not causal lock-key
attribution, a dynamic pipeline, or a throughput estimate. It predates the neutral
f8dd correctness fix and must be re-baselined before production experimentation.
The selected workload remains unchanged; no server-side TPC-C transaction program.

## Force identity and ownership contract

The WAL owner captures a reusable target with database/provider identity, WAL
generation, a non-reused live token, half-open start/end offsets, covered record
count and final covered commit sequence **before** invoking force. Lineage and
token distinguish reuse, a reopened provider and generation rotation. Captured
fields remain immutable while owned. Token exhaustion is a resource status;
never wrap into an identity that can be mistaken for a prior operation.

A successful local force advances only the captured local prefix. The operating
system may persist later bytes too; that is not evidence to acknowledge them.
Quorum consumers read and replicate this exact captured range, and success at
the configured acknowledgement level remains separate from local force success.
A result is not an independently writable source of durable truth.

The engine retains its corresponding cohort identity (ordered member CSNs,
required WAL target, publication ownership and admitted resources) until the
WAL owner proves completion and the existing transaction owner terminalizes it.
A stale, foreign, mutated or released target cannot complete or release another
cohort. Release consumes only that identity exactly once; caller/result reuse
cannot clear a newer pending range. All River-owned direct, group, logical-stream,
quorum and maintenance callers use the replacement contract in the same delivery.
Existing on-disk decisions and formats remain unchanged.

In the serial architecture delivery the current admitted storage retains one
cohort. Do not preallocate another page workspace, add an idle thread or install
an unused multi-cohort state machine. Exact identity/coverage, failure and stale
reuse tests must exercise the actual synchronous owner. Concurrent append remains
unsupported until f1bb proves the complete cross-owner contract.

## Canonical transition and failure table

These are contract stages, not a request for another overlapping production enum.
Existing owners carry the transitions; diagnostics observe them.

| Stage / trigger | Ownership and legal progress | Failure or cancellation outcome |
| --- | --- | --- |
| Logical seal and queue admission | Session retains immutable logical work and admitted demand; coordinator owns queue position | Pre-append cancellation/impossible demand cleans up once without durable side effects; transient pressure uses existing cancellable admission |
| Physical preflight | One canonical writer owns operation scratch, selected prefix and prepared generations | Failure before WAL acceptance rolls back prepared work and reservations; no direct fallback |
| Decision append | WAL establishes ordered bytes and complete decisions; sequence order is preserved | Any uncertain partial write fences before admission/outcome wakeup; do not claim clean abort after irreversible acceptance |
| Prepared installation and visible publication | Existing transaction barrier installs one visibility prefix, hands off locks and keeps transaction handles pending durability | Installation failure after append fences; no visible rollback or early success |
| Force-target capture | WAL freezes exact lineage/token/range/count/final CSN | Invalid/stale identity is rejected without modifying another target; no wraparound |
| Local force outstanding | Publication pins protect every unforced generation; observed dependents may execute but their delivery barrier remains | Interrupt/cancellation cannot turn an appended decision into an ordinary rollback; uncertainty fences dependents before wakeup |
| Local force succeeds | Advance the captured local prefix only; retain configured quorum obligation | Quorum failure can occur after local bytes are durable; return the existing indeterminate/fenced outcome, never acknowledge merely local success |
| Required durability succeeds | Existing writer/publication and transaction owners validate identity, release corresponding resources and publish terminal outcomes in order | Cleanup inconsistency fences before further admission; exactly-once finalization remains authoritative |
| Force or quorum fails | Fence provider/store, accepted pending cohorts and dependent delivery; stop new publication/admission | Already acknowledged durable prefixes retain their truth; unacknowledged suffix outcomes remain uncertain and recovery-owned |
| Graceful close/checkpoint/rotation | Drain or fence retained work before closing/replacing its provider; no target outlives its generation | Never close a file under a running force or let late completion certify a new generation |
| Crash/reopen | Recover complete valid decisions from a validated base and WAL; ignore/discard incomplete records per existing recovery | Unacknowledged complete decisions may recover; acknowledged decisions must recover. A permanent live-instance fence is not persisted as a substitute for recovery |

## Overlap-specific invariants and execution boundary

The implementation must retain a gap-free ordered prefix of pending cohorts.
Cohort descriptors consume the existing active-transaction allowance; retained
page generations consume the existing page-frame budget. Admission sums retained
and prospective demand using the authorities audited by ca05/5b3e/6f81. No fixed
"two cohort" prototype cap and no multiplication of the entire page pool per
transaction. On pressure, complete/reclaim the oldest durability obligation or
return the specified cancellable/impossible status before new side effects.

Published cohort state is detached from reusable preparation scratch. A successor
may compile against predecessor-visible generations, including the same page,
without overwriting predecessor pin ownership or WAL coverage. Finishing prefix A
must neither unpin successor B's generations nor unblock a result that observed B.
Checkpoint, vacuum, direct commits and rotation must use the same retained-work
exclusion rather than interpreting a cleared preparation phase as an empty pipeline.

The missing execution arrangement requires an explicit reviewed architecture
decision before f1bb implementation. The proposed minimal arrangement retains one
canonical commit writer and its one queue; a WAL-owned bounded handoff executes
one captured blocking local force at a time. It owns no transaction preparation,
publication, lock policy or acknowledgement. Completion returns to the canonical
writer. This is a new force-I/O execution responsibility and must not be disguised
as ordinary refactoring or a second commit executor. No thread/worker lands in 7352.

The force task must not read mutable append cursors, shared encode/read/result
scratch, or mutate transaction/page state. The provider contract must explicitly
support force concurrent with positional append, preserve buffer/result ownership,
and exclude close/truncate/rotation until I/O joins. Current `DurableFile` documents
positional calls but does not yet specify this concurrency contract: f1bb cannot
assume it from the NIO adapter alone. Its platform and fault providers must be
updated and tested together under the accepted execution decision.

Existing quorum replication may remain writer-serialized after local force, using
an exact captured cursor. Do not concurrently reuse the provider's read/I/O scratch
or claim quorum-level overlap without separate proof. Scheduling must prioritize
completed force reclamation enough to prevent pending budgets from blocking their
own release; an empty eligible queue triggers force without an artificial batching
delay. Low-contention and single-worker controls test the added handoff cost.

## Required proof by delivery

For 7352: exact target range/result/cursor agreement; foreign/stale/reused target
rejection; local and quorum failures; logical-stream continuation/final boundaries;
pre-force page-write and result-delivery exclusion; direct/group equivalence;
close/rotation identity; no added steady-state allocations/copies; existing recovery
and failure suites; two fresh pinned-JDK short baselines/candidates and longer
interleaving on directional change; slopmark and policy delta; clean full tests.

For f1bb, add deterministic held-force tests that prove a queued successor is
physically prepared, appended and published before predecessor force completion;
include successive modifications of one page and at least three pending cohorts
when admitted resources permit. Completing A alone leaves B-dependent reads and
acknowledgements blocked. Fail A after B publication; cover partial B append,
quorum failure, cancellation, close, resource exhaustion, wakeup races and crash
at each irreversible boundary. Require exact terminal transactions, pins, receipts,
locks/waiters and retained snapshots, with bounded allocation and no double cleanup.

Measure actual enqueue-to-selection delay, physical preparation, force overlap,
lock residence, force/cohort distributions and latency alongside TPS. A scheduling
change may reduce queue/lock residence before changing forces-per-write; declare
that mechanism in advance rather than requiring an unrelated batching denominator.
Accept no repeated TPS regression on low-contention or single-worker controls.
The main unchanged standard workload needs a repeated benefit outside adjacent
variation; if absent, reject the optimization or open the precisely observed blocker.

## Dependency disposition

- b368's previous P0 dependency was a scheduling gate on evidence-only design;
  mapping replaced it with accepted source/probe/correctness evidence and kept
  its P0 link. This does not assert that the P0 matrix passed.
- 7352 requires b368 acceptance and its own exact force/lineage/cleanup/fault,
  review, allocation/slopmark, matched TPS and clean-full gates. Independent
  review after mapping confirmed 1dda is historical P1 promotion ordering for
  this narrowly serial change: causal lock aggregates, snapshot export and the
  full scaling campaign establish no missing force-target invariant. 7352 now
  links 1dda; the hard edge is removed explicitly, not bypassed. Any expansion
  to concurrent retention, scheduling, lock or admission changes invalidates
  this disposition. This neither certifies P0 nor removes f1bb's existing gates.
- f1bb retains 7352, 1dda and 6f81 (transitively ca05/5b3e), plus acceptance of the
  force-I/O execution/provider decision owned by `tic-92e3`. Do not start it with any unresolved
  lifetime, resource or provider contract.
- Old baseline tickets 288d/e2be/50e8 have implementation ancestors on current
  master; their delivery status must be reconciled with exact gate/review evidence.
  The 5cc0 and af29 feature commits are not ancestors; do not infer delivery merely
  from passing current tests or an existing worktree.
- riverd retains its supported-platform/security gate. Its instance authority
  currently requires qualified Linux ext4/xfs; a macOS TPS result cannot certify
  installed-lifecycle acceptance. External harness migration remains separately
  owned and its exact contract must be verified before comparison promotion.

## Review and acceptance

Independent concurrency/recovery reviewer `review_page_reuse` accepted the
current-source trace, serial force-target/retained-identity contract and full
failure matrix. The review required correction of one ticket-ID typo and the
remaining normative post-force/denominator clauses in f1bb; both are corrected.

The design is accepted for the 7352 serial architecture scope, subject to its
production prerequisites. Dynamic overlap remains unapproved until 92e3 accepts
the new force-I/O/provider/resource contract and f1bb meets its other gates.
No new benchmark or production test result is claimed by this evidence-only
reconciliation. Ticket graph validation and whitespace checks pass.
