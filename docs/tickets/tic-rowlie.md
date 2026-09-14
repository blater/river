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
Accepted `tic-b368`/`tic-7352` remain historical dependencies. The independent
P0 gate `tic-1dda` remains mandatory where currently specified, especially f1bb.
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

### Existing gate blocks completion, 2026-09-14

The independent current-source readiness audit in tic-1dda confirms its required
correlated, controlled Payment/New Order mixed-isolation reproducer is still
missing. Phase-start barriers and the district-lock preflight are insufficient.
The gap is already recorded in the historical failed P0 evidence; af29/8e74 did
not deliver it. Ordinary workload runs cannot substitute for that requirement.

Keep this epic and all unpassed children open. No force overlap, cohort admission
or lock removal is implemented, and no P1 checkpoint is issued. The user forbids
increasing scope, so this delivery records the existing blocker without creating
a new fixture workstream or bypassing the mandatory P0 dependency. Execution and
protocol decisions do not certify this WAL gate.
