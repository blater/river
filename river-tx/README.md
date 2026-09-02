# River transaction kernel

The production transaction kernel supports bounded concurrent transaction
handles, read-committed statement snapshots, repeatable-read transaction
snapshots, and deferred indexed inserts, updates, and deletes governed by byte
budgets rather than fixed operation counts. Append-only row versions preserve
repeatable-read snapshots across update, delete, and key reinsertion. Commit
holds the publication barrier across WAL force, heap/index publication,
active-set removal, and the final outcome. Transaction IDs and commit CSNs
resume from the recovered WAL.

The same publication barrier admits synchronous maintenance only when the
active transaction and lock sets are empty. The first consumer uses this seam
to compact obsolete row versions without invalidating a retained snapshot.

The lock kernel uses one byte-bounded canonical table, reactive FIFO scheduler,
and deadlock graph for exact resources, signed-scalar keys/ranges, and unsigned
lexicographic tuple keys/prefix ranges. Tuple request buffers are borrowed for
the call and copied into reusable envelope-governed storage only when a new
canonical resource is created. Half-open scalar boundaries and prefix-aware
tuple cuts provide missing-key and serializable phantom protection without a
fixed key-width or lock-count limit. Physical undo/savepoints, durable non-final
outcomes, and concurrent horizon-based pruning remain later transaction stages;
abort currently discards the deferred mutation set and releases its locks.
