# Erebor final review handoff

Date: 2026-09-29 UTC. Branch: `feature/tic-erebor-clustered-row-store`.
Base: pushed stable `origin/master` at `2ada6350`. This branch is one
unpromoted replacement; its intermediate commits are not separate releases.

The implementation replaces descriptor scalar row authority with a clustered
tuple row value, adds direct secondary primary locators and the conditional
declared-primary identity map, and extends the existing logical WAL and page
generation machinery for values, overflow and reclamation. The layout and
format decision is [ADR 0015](../../adr/0015-clustered-relational-row-store.md).
The [design review](2026-09-29-tic-erebor-design-review.md) and
[implementation progress review](2026-09-29-tic-erebor-implementation-review.md)
preceded the completed replacement; neither is final code approval.

The serial clean full `check` passed after the production lock correction at
`64c299ac`. Later changes added test and evidence only. The focused
`IndexedRelationalWalCommitTest` and
`RelationalDescriptorTupleDeltaPlanTest` classes passed after those additions.
The [Stock Level artifacts](2026-09-29-tic-erebor-candidate-stock-level.md)
were accepted by the owner for the local throughput gate, with their variation
preserved. The [write-lock diagnostic](2026-09-29-tic-erebor-clustered-write-lock.md)
passed New Order/Payment invariants and cleanup on the low-power host, without
establishing a throughput ratio. The [overflow evidence](2026-09-29-tic-erebor-overflow-reclamation.md)
covers page reuse, old snapshots, grouped same-leaf replacement, value-growth
root split, history pressure and a greater-than-63-page logical mutation.
The [identity audit](2026-09-29-tic-erebor-identity-routing.md) records direct
read routes and logical mapping mutation counts.

Independent final durable-format, recovery and concurrency review remains
required by [AGENTS.md](../../../AGENTS.md) and the ticket. Review should
inspect exact WAL replay of overflow retirement/reuse, old leaf references,
group force dependencies, the split membership sequence, and the additional
tuple-key lock on non-key updates. Two promotion questions are still open:

- Exact extra identity-map page changes, copied bytes, WAL bytes, history
  occupancy, checkpoint writes and CPU have not been isolated from a matched
  write workload. The one low-power mixed diagnostic establishes correctness,
  not those costs.
- Reclamation currently frees at most one eligible overflow page per descriptor
  suboperation. At page-ID exhaustion a large atomic operation can fail even
  with several eligible retired pages; the [overflow evidence](2026-09-29-tic-erebor-overflow-reclamation.md)
  records the status and smaller-transaction recovery option. Review must
  decide whether this meets the capacity contract or requires multi-page
  reclamation in the logical WAL format.

Do not merge, tag a performance checkpoint or designate a baseline until the
independent review resolves these questions and records its findings.
