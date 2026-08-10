# River transaction kernel

The first production consumer supports bounded concurrent transaction handles,
read-committed statement snapshots, repeatable-read transaction snapshots, and
up to four deferred indexed inserts per transaction. Exclusive unique-key locks
are retained until commit or abort. Normal batches use one compact WAL record;
batches that split an index leaf use one atomic page-image record. Commit holds
the publication barrier across WAL force, heap/index publication, active-set
removal, and the final outcome. Transaction IDs and commit CSNs resume from the
recovered WAL.

Serializable sessions are rejected until row and range locks are integrated.
Physical undo/savepoints, durable non-final outcomes, MVCC update chains, and
vacuum remain the next transaction stages; abort currently discards only the
deferred insert set and releases its key locks.
