# River journal API

This module is the provider-independent contract for bounded ordered journal
reservation/publication, named durability, lineage fencing, inspection,
idempotent outcome lookup, and semantic WAL retention. It deliberately does
not define WAL blocks/segments, a force implementation, or a consensus
algorithm.

The contract keeps four units separate:

- a logical `JournalPosition` is database-incarnation, journal-generation,
  and sequence;
- local WAL ranges use a lineage-qualified `recordStartLsn` and exclusive
  `recordEndLsnExclusive`;
- local stable media is represented by an exclusive `durableEndLsnExclusive`;
- transaction visibility order is an explicit `CommitSequence` mapping and is
  never inferred from a journal position or LSN.

Common reserve, publish, wait, and poll operations populate reusable
caller-owned carriers with flattened primitive lineage fields. Immutable
position/range records are reserved for cold inspection and control-plane
boundaries. A durability deadline of zero means no timeout. Cancellation of a
reservation must publish a bounded repair/tombstone so a public gap-free
frontier can never silently skip the abandoned position.

Durable semantic retention leases are explicitly reopened under the current
node incarnation after restart; pre-restart handles remain fenced.

`JournalFrontierSnapshot` contains only journal-owned facts: prepared,
memory-replicated, journal-committed, local-WAL-durable, and
quorum-WAL-durable prefixes. It intentionally has no mutable `visibleCsn`,
`durableRecovery`, or `safeTruncate` counter.

The warmed fake-provider test proves that its reused
reserve-to-publish-to-wait/poll path allocates no more than measurement noise.
Production allocation, copy, retained-byte, ring-capacity, and wait-ticket
budgets remain provisional until the complete P09 evidence set establishes
numeric limits.
