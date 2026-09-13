---
id: tic-92e3
status: open
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
