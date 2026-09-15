---
id: tic-92e3
status: in_progress
type: investigation
priority: 2
assignee: blater
parent: tic-rowlie
delivery: evidence
deps:
    - tic-b368
    - tic-7352
    - tic-5b3e
    - tic-6f81
tags:
    - wal
    - architecture
    - concurrency
created: 2026-09-07T08:39:55.505404Z
---
# Specify bounded WAL force I/O and provider concurrency

Accept the exact WAL-owned force I/O handoff, provider concurrency and retained-cohort budget/lifetime contract before tic-f1bb implementation. This follows the serial tic-7352 checkpoint and cumulative admission audit/delivery; it must preserve one canonical commit writer and queue.

### Acceptance and scope

Evidence only: one explicit architecture decision for the force-I/O owner and
its bounded handoff. Identify acquire/release ordering, immutable target and
result ownership, close/rotation joining, exact-prefix quorum consumption,
provider support for force with positional writes, and the budget/reservation
lifetime for every retained cohort. Use existing authorities and prove how
completion/reclamation cannot deadlock admission. Specify the fault/provider
matrix and the smallest atomic f1bb implementation.

Do not implement a thread, queue or provider change here. Reject the design if
it creates another transaction executor or relies on undocumented provider
concurrency. Independent concurrency/recovery/platform review must accept the
contract before f1bb code; inability to meet it is an explicit blocked decision,
not permission to weaken durability. No new TPS claim or fresh workload is
required for this design-only delivery.

### 2026-09-13 performance realignment

Moved from `tic-e5ff` to `tic-rowlie`. Existing dependencies and unfulfilled correctness gates remain authoritative. Follow [the current handover](../plans/performance-three-epics-handover.md); this move certifies no implementation or performance outcome.

### 2026-09-13 performance realignment

The investigation must use current mapped WAL, atomic group footer and range-sync owners (6a91/9f2c/c7e2), including mapped writes concurrent with captured-range force, footer ordering, mapping lifetime and rotation/close. A positional-write-only provider proof is insufficient. Reconcile the accepted historical b368 design against these changes and record one complete execution/provider contract before f1bb starts.

### Historical WAL readiness, 2026-09-14 (superseded)

The user deferred P0 scaling/accounting and removed it from WAL admission.
ca05 is accepted and closed. The remaining chain is blocked by the existing
[5b3e physical admission boundary](tic-5b3e.md): cumulative frozen-page demand
is discovered after staging, with no accepted pre-stage prefix contract within
its current scope. Keep this ticket open and its remaining dependencies intact;
no implementation, acceptance claim or new follow-up ticket is added here.

### Current WAL readiness, 2026-09-15

`tic-5b3e` is closed with exact per-page admission and prefix rollback.
`tic-6f81` is closed with the reviewed chunked-WAL contract satisfied by current
source and existing evidence. This ticket is now the next force-overlap
dependency; its accepted execution/provider/resource contract remains required
before `tic-f1bb` implementation.

### Accepted force-overlap contract, 2026-09-15

The [reviewed force-overlap contract](../delivery/evidence/2026-09-15-tic-92e3-force-contract.md)
is accepted against source commit `61d24d00`. It preserves one writer and queue,
defines the one-slot local-force handoff, exact local-durability versus quorum
ordering, NIO mapped-view lifetime, completion-priority rule, and checked
retained-byte accounting. Independent concurrency/provider review accepted the
contract after its native-wrapper scope, mapping pins and thread confinement,
and resource formulas were corrected. This evidence delivery implements no
thread, provider, transaction, or workload change.
