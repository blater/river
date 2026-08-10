# River transaction kernel

The first production consumer supports bounded concurrent transaction handles,
read-committed statement snapshots, repeatable-read transaction snapshots, and
up to 64 deferred indexed inserts, updates, or deletes per transaction.
Exclusive unique-key locks are retained until commit or abort. Normal batches
use one compact WAL record; insert batches that split an index leaf use one
atomic page-image record. Append-only row versions preserve repeatable-read
snapshots across update, delete, and key reinsertion. Commit holds the
publication barrier across WAL force, heap/index publication, active-set
removal, and the final outcome. Transaction IDs and commit CSNs resume from the
recovered WAL.

The same publication barrier admits synchronous maintenance only when the
active transaction and lock sets are empty. The first consumer uses this seam
to compact obsolete row versions without invalidating a retained snapshot.

Serializable point reads and inserts are supported with retained shared and
exclusive key locks, including missing-key protection and in-transaction lock
upgrade. Range scans are not yet exposed transactionally, so this does not
claim phantom protection for scans. Physical undo/savepoints, durable non-final
outcomes, and concurrent horizon-based pruning remain later transaction stages;
abort currently discards the deferred mutation set and releases its locks.
