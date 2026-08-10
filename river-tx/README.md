# River transaction kernel

The first production consumer supports bounded concurrent transaction handles,
read-committed statement snapshots, repeatable-read transaction snapshots, and
one deferred indexed insert per transaction. Commit holds the publication
barrier across WAL force, heap/index publication, active-set removal, and the
final outcome. Transaction IDs and commit CSNs resume from the recovered WAL.

Serializable sessions are rejected until row/key/range locks are integrated.
Multiple writes, rollback/savepoints, durable non-final outcomes, MVCC update
chains, and vacuum remain the next transaction stages; this slice makes no
claim for them.
