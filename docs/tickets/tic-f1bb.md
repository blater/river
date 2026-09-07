---
id: tic-f1bb
status: open
type: story
assignee: blater
parent: tic-e5ff
delivery: code
tags:
    - performance
    - tpcc
    - p1
    - wal
    - transactions
deps:
    - tic-b368
    - tic-7352
    - tic-1dda
    - tic-6f81
    - tic-92e3
links:
    - tic-32b3
created: 2026-09-04T15:10:07.446626Z
---
# Overlap next-cohort physical work with WAL force

The current group path already appends decisions, publishes visibility and hands
off locks before force; observed result delivery and commit acknowledgement wait
for their required durability. The canonical writer nevertheless blocks in that
force and cannot prepare the next queued cohort. Remove that serialization while
preserving the existing SQL/workload/isolation/durability contract.

## Outcome

Eligible queued work is physically prepared, appended and published while an
earlier captured local force is outstanding. Repeated matched standard-workload
TPS improves and declared queue/lock-residence or force/cohort denominators move.
The existing transaction, WAL, publication and budget owners remain authoritative.

## In scope and maximum change shape

Implement the accepted b368/92e3 contract as one end-to-end extension of the
existing path. Replace single-pending-cohort assumptions atomically: retained
page-generation pins, preparation scratch reuse, resource-accounted descriptors,
pending suffix visibility/durability, ordered completion/fencing and force-I/O
handoff. All River-owned direct/group/quorum/maintenance callers change together.
The serial identity foundation is delivered first by 7352.

One physical writer and one commit queue remain. The WAL force-I/O responsibility
must be explicitly accepted under 92e3, with bounded handoff and provider/memory
ownership proof. No duplicate commit executor, outcome state machine, fallback,
logical representation, dependency policy or compatibility mode.

## Non-goals

No new WAL format, weaker acknowledgement, SQL transaction-program collapse,
client/protocol changes, lock/retry tuning or artificial batching delay. Merely
repeating existing pre-force publication or rearranging work after force returns
is not the selected mechanism. No unrelated lifecycle or transaction cleanup.

## Acceptance

- Held-force tests prove physical successor work progresses before force return;
  successive mutations of the same page retain exact generations and dependencies.
- Completing prefix A cannot acknowledge/unpin/unblock pending suffix B. Force,
  partial append, quorum, close/rotation, cancellation and recovery faults pass
  the accepted matrix, with exact transactions, snapshots, pins, receipts and locks.
- Retained and prospective demand share existing budget authorities. No arbitrary
  cohort cap, unbounded queue/arena, extra per-row allocation or unexplained copy.
- Capture fresh pinned-JDK identical short TPS baselines/candidates, followed by
  longer interleaved controls for the performance claim. Declare the mechanism
  before edits: actual enqueue-to-selection delay and physical-work/force overlap,
  predicted lock residence, plus force/cohort distributions. Queue/lock residence
  may move before force-per-write; do not force a batching explanation onto it.
- Require repeated main-workload benefit and investigate directional latency/TPS
  shifts in single-worker and low-contention controls. All invariants/outcomes and
  capture/cleanup receipts must pass. Slopmark, independent concurrency/recovery
  review, affected tests, clean full gate and pushed checkpoint complete delivery.

## Stop conditions and readiness

Do not begin until every dependency closes and the 92e3 execution/provider/resource
contract is accepted. Reject the optimization if no declared mechanism moves, no
repeatable benefit is demonstrated, or a regression/cleanup/failure remains
unexplained. Open the exact discovered blocker separately rather than retain
unproductive scheduling complexity. The b368 timing model is not a TPS promise.

This reconciliation supersedes the 2026-09-04 post-force publication assumptions
and requirement that force-per-write alone demonstrate progress. The canonical
mapping remains in [tic-e5ff](tic-e5ff.md).
