---
id: tic-rowlie
status: open
type: epic
priority: 1
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - wal
    - concurrency
created: 2026-09-13T11:46:05.49846Z
---
# Overlap commit preparation with durable WAL force

### Outcome

Let the existing canonical physical writer prepare an eligible successor while
an earlier captured local WAL force is outstanding. Preserve exact prefix
acknowledgement, observed-read barriers, resource accounting and recovery.
Group commit, pre-force publication, lock handoff and force-prefix identities
already exist; merely recreating them is not an optimization.

### Children and order

Retain the existing source-backed contracts and move these open tickets here:
`tic-ca05` → `tic-5b3e` → `tic-6f81` → `tic-92e3` → `tic-f1bb`.
Accepted `tic-b368`/`tic-7352` remain historical dependencies. The user removed P0 gate `tic-1dda` from WAL admission on 2026-09-14;
its scaling and accounting work is deferred without claiming it passed.
The adjacent `tic-4d14` → `tic-845d` audit/removal stream may remove only one
proved redundant holding rule; it is not permission for broad lock redesign.
`tic-7a5a` owns the composed promotion decision and concludes this epic.

### Current-source reconciliation

Audit mapped WAL (`tic-6a91`), atomic commit groups (`tic-9f2c`) and range sync
(`tic-c7e2`) before designing force concurrency. The provider contract must cover
mapped writes overlapping force, captured range/footer identity, page generations,
mapping lifetime, rotation/close and failure fencing. Old positional-write-only
assumptions do not establish safety on today's path.

### Completion

All admitted children meet their existing gates; held-force tests demonstrate
successor physical progress before force returns, and longer matched measurements
show useful overlap/queue movement and repeatable main-workload benefit. Prefix A
must never acknowledge, release or certify unforced suffix B. One writer, one
commit queue and existing outcome/budget owners remain authoritative.

The pure enabling tickets may establish correctness with neutral throughput;
f1bb must establish its declared mechanism and benefit. No extra batching delay,
weaker durability or fixed convenience caps. Shared protocol in
[the handover](../plans/performance-three-epics-handover.md) applies. Formal parity
is separately gated; it is not a prerequisite for diagnostic development.

### Current gate and impact priority, 2026-09-14

The general concurrency reproducer tic-b1b7 is delivered. Resumed tic-1dda ran
two of 40 planned cells and stopped: warmup client attempts and retry dispositions
are omitted, so the required phase reconciliation cannot pass. Exemplar capacity
also saturated; a configuration increase cannot restore the missing client data.
No scaling result or P0 acceptance follows from those two samples.

Prioritize the existing ca05 → 5b3e → 6f81 → 92e3 → f1bb critical path after
the user-directed removal of P0 admission. The selected performance outcome is successor physical progress
while force is outstanding; 5b3e is its resource-safety enabler. Keep the audit
and provider decisions bounded to their current contracts, reusing existing proof.

Defer 4d14 → 845d until the force-overlap decision. No lock removal is ready
without one proved redundant rule and a credible service-cost or blocking effect.
Keep 7a5a as final acceptance with its existing dependencies, including the lock
stream's eventual disposition. The retained osgiliath audit does not identify a
fix; resume it for actionable new evidence or an obstruction of this path.

The user explicitly deferred P0 scaling and warmup accounting and removed
tic-1dda dependencies from ca05, 4d14 and f1bb. All other dependencies and the
WAL-specific durability, concurrency, recovery and resource checks remain intact.
Add no tickets or speculative mechanisms; the deferred P0 campaign remains
unpassed and outside this implementation path. The [current backlog](../backlog-kanban.md) owns the scheduling order.

### Delivery disposition, 2026-09-14

Completed: user-directed removal of P0 admission and ca05 logical preparation
audit (43 tests passed; independent review; evidence commit 2333ab60).
Blocked: 5b3e has no pre-staging demand/ownership contract for cumulative frozen
page generations within its present change boundary. Its exact source and
existing-test evidence are recorded on that ticket; this blocks 6f81, 92e3 and
f1bb through their remaining dependencies. No runtime optimization is delivered
or claimed. Deferred: P0 scaling/accounting, the conditional 4d14/845d lock
stream, and 7a5a promotion pending accepted implementations. Keep osgiliath open
for actionable new checkpoint evidence. No new tickets or mechanisms were added.

### Force-overlap and lock-audit disposition, 2026-09-15

The force-overlap mechanism is accepted and pushed at
`perf-checkpoint-20260915-force-overlap`
(`f51342a7b5e0e6030bd2856264c3c379c9a4d96d`). The adjacent read-only
[`tic-4d14` audit](../delivery/evidence/2026-09-15-tic-4d14-lock-audit.md)
reconciles the material holding and block-event classes and exhausts the retained
family/release evidence, but cannot meet its mandatory step, blocked-time and
per-rule service-cost attribution gates. It selects no removal rule. Keep
`tic-4d14`, `tic-845d`, this epic and `tic-7a5a` open; no lock implementation or
promotion run is ready. P0 scaling/accounting remains separately deferred and
unpassed.
