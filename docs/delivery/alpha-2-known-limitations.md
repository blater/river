# River 0.1.0-alpha.2

River 0.1.0-alpha.2 is a pre-V1 evaluation checkpoint. It combines the
bounded n-table join work, the complete P4C correlated-subquery consumer
surface, and the reviewed Alpha 3 capacity-format foundation. It is not a
production release. Use disposable data and keep independent backups.

The complete Alpha 1–Alpha 3 sequence and numeric limits are maintained in the
[alpha delivery roadmap](alpha-roadmap.md).

## Included in this alpha

- Durable heap and B+tree storage, WAL recovery, checkpoints, transactions,
  indexes, constraints, sequences, views, and quiescent backup/restore.
- Embedded SQL plus authenticated TLS loopback JDBC and CLI access.
- Typed parameters/results, DML, aggregation, grouping/HAVING, DISTINCT,
  ordering and bounded spill, EXPLAIN, and EXPLAIN ANALYZE.
- Two-to-eight-role inner/left joins with durable lineage, bounded nested/hash/
  merge planning, direct and deepest-derived views, and durable `ANALYZE`
  statistics for deterministic SQL-order costing.
- Correlated scalar, EXISTS, IN and comparison subqueries across direct,
  point, aggregate, GROUP/HAVING, DISTINCT, ordered/spilled, joined, and P3
  consumers, with three-valued logic and failure-atomic cleanup.
- The A3C-0 durable contracts for 255-column masks and protocol envelopes,
  segmented catalogs, long logical/version identities, primitive and typed
  tuple B+tree pages, WAL page batches, checkpoint manifests, and resumable
  vacuum progress.

## Known limitations

- This is pre-V1 software. Public APIs and durable formats may change
  incompatibly. River does not provide upgrade adapters for this alpha.
- A3C-0 freezes capacity formats; the runtime removes the legacy 65,536
  physical row/version ceiling. WAL and checkpoint metadata now carry positive
  logical row IDs through 4,294,967,294, and an audited checkpoint test covers
  a 3,000,000,000-row append-only manifest. The transitional runtime remains
  bounded by positive-int page IDs and resident page frames and is not yet
  qualified for billion-row tables or a standard one-warehouse TPC-C load.
  A3C-1 and A3C-2 complete the disk-backed scale path.
- The network service is restricted to authenticated TLS loopback use.
  Non-loopback deployment is unsupported.
- The complete crash, recovery, isolation-history, fault-injection,
  bounded-growth, and long-running soak promotion matrices are unfinished.
  Focused recovery evidence is not a production durability guarantee.
- Backup is quiescent/offline. Online backup, in-place upgrade, migration,
  repair automation, and production operational tooling are incomplete.
- Nested parameters and durable nested views, broader cross-column or
  contextual CHECK expressions, multiple/nondeep JOIN blocks, and generalized
  JOIN/correlation combinations fail closed or remain unsupported.
- Join statistics remain unchanged after DML until `ANALYZE` is rerun. The
  alpha costs strategies in SQL role order; physical inner-island reordering
  is deferred. Hash input beyond the in-memory envelope uses the documented
  stable bounded fallback rather than partitioned spill hashing.
- Replication, failover, online schema migration, and production observability
  are not included.
- JDK 25 is required. Packaging is source/Gradle based; no supported native
  installer or operating-system service package is supplied.

Unsupported SQL and boundary operations return a River status or JDBC SQLSTATE
rather than silently changing semantics. The normative admitted surface is in
the [SQL conformance profile](../compatibility/sql-conformance-profile.md), and
delivery status is in the [implementation ledger](implementation-status.md).

## Validation boundary

The release tag identifies the exact validated source. SQL, engine, backup,
format, protocol, and JDBC suites pass, as do dependency, static design-debt,
and repository-diff checks.
