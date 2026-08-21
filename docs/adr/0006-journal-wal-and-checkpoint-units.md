# ADR 0006: Journal, WAL, and checkpoint units

Status: Accepted

## Context

The local WAL is River's first journal provider, but logical journal order,
physical byte addresses, transaction visibility, checkpoint coverage, and
restart durability are different facts. Earlier plan wording sometimes used a
single "durable LSN" as both a record address and an inclusive frontier.

## Decision

Use these units and half-open conventions:

- `JournalPosition` is `(DatabaseIncarnation, journalGeneration, sequence)` and
  identifies logical ordered entries independently of local layout.
- `WalRecordPointer` is `(DatabaseIncarnation, WalGeneration,
  recordStartLsn)`. `recordStartLsn` is the replica-local offset of the first
  byte of a WAL record in that generation.
- `WalRecordRange` adds `recordEndLsn`, the exclusive offset after the complete
  padded frame. A record occupies `[recordStartLsn, recordEndLsn)` only inside
  the pointer's exact database incarnation and WAL generation.
- `PageWalToken` is the complete `WalRecordRange` stored on a page. Transaction
  predecessor/undo references use `WalRecordPointer`, never a bare offset.
- `DurableWalEnd` is `(DatabaseIncarnation, WalGeneration,
  durableEndLsnExclusive)`, the highest gap-free byte boundary known forced in
  that exact lineage. A record is restart-durable exactly when its range and
  durable end have equal lineage and
  `recordEndLsn <= durableEndLsnExclusive`.
- WAL blocks are independently checksummed framing units; records may fragment
  across blocks. Segments are named, header-validated allocation/retention
  units and never define transaction or journal atomicity.

The local append lifecycle has distinct internal states: reserved byte range,
fully encoded/published gap-free range, written-complete range, and forced
`DurableWalEnd`. Reservation or local acceptance is not publication; write
completion is not force; memory publication is not restart durability. On
restart, validated scanning establishes a recovered durable tail from stable
blocks rather than trusting a pre-crash in-memory counter.

A `JournalPositionMapping` records a logical position and its local half-open
LSN range. A transaction decision is an ordinary versioned journal record whose
complete required predecessor records must precede it. The local provider may
advance journal commitment at publication, but `LOCAL_DURABLE` waits for its
decision range and every earlier byte through a same-lineage `DurableWalEnd`.

WAL byte offsets may reset only when `WalGeneration` changes. A generation
transition is recorded and forced in the prior lineage and installed in the
database control record. All page tokens, transaction predecessor pointers,
checkpoint DPT entries, retention leases, and journal mappings retain their
generation. A generation cannot be deleted or its number/offset pair reused
while any durable page, checkpoint, backup, outcome, or recovery chain can
reference it. An incompatible lineage on open is corruption and fences the
database; River never compares offsets across lineages.

A fuzzy `CheckpointId` names one begin/end recovery boundary. End-checkpoint
records contain bounded chunks of active-transaction state, lineage-qualified
dirty-page recovery tokens,
transaction-ID high-water, visible commit high-water, and required recovery
metadata. Only a complete forced set followed by atomic durable master-control
installation becomes the latest checkpoint. It does not turn the in-place data
files into an immutable snapshot.

## Invariants

- Reservation precedes page latching; force and backpressure waits occur after
  page latches are released.
- Published WAL has no unexplained hole; an abandoned reservation follows a
  bounded repair/fail-stop protocol.
- Page writeback requires the page image's `PageWalToken.recordEndLsn` to be at
  or below a `DurableWalEnd` with identical database incarnation and WAL
  generation; it never compares bare offsets or substitutes a logical, quorum,
  or checkpoint frontier.
- Active-tail truncation stops at the last complete validated WAL block/record;
  interior sealed-segment corruption is fatal.
- Checkpoint publication cannot advance from partial chunks, unforced WAL, or
  a control record whose directory installation is not durable.
- The inspector uses a read-only `river-wal` decoder/scanner dependency and
  cannot append, force, repair, or acquire engine ownership.

## Consequences

Names expose off-by-one and lineage errors at API boundaries. Physical block
size, segment size, ring capacity, fragmentation policy, and group-commit
timing remain measured format parameters, not decisions in this ADR.

## Alternatives

- Using one integer for journal position, record start, durable end, and CSN
  was rejected as unsafe across providers and restarts.
- Inclusive durable LSN was rejected because it is ambiguous for variable-size
  and fragmented records.
- Querying WAL as a current-state overlay was rejected.

## Required evidence

- P10 provider-independent mapping/frontier fake tests.
- P09 WAL reservation, framing, block/segment, force, and group-commit results
  through `river-bench`; P05 provides the numeric budgets.
- Crash/corruption tests at every append, rollover, force, checkpoint chunk,
  and control-install boundary.
- Same-version WAL fixture acceptance and unknown/incompatible version rejection.

## Authoritative context

- [High-level journal/WAL plan](../plans/river-high-level-plan.md)
- [Implementation plan K03, K04, and K11](../plans/river-project-implementation-plan.md)
- [Replicated journal plan](../plans/river-replicated-journal-durability-plan.md)
