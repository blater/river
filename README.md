# River

River is a relational database implemented in Java. Its target is a
high-performance, crash-safe single-node database with SQL and JDBC access,
followed by a replicated journal and operational failover.

For the current embedded lifecycle, SQL client, and essential administration
rules, see the [River database how-to](HOWTO.md).

### Current capabilities

 Storage and recovery:

  - Durable control files, Write-Ahead-Log (WAL), group commit, checkpoints and WAL rotation.
  - Multi-page heap storage and multi-level B+trees.
  - Unique, duplicate-secondary and nullable indexes.
  - Recovery of committed operations before page flush.
  - Torn-checkpoint-page repair and corruption rejection.
  - Quiescent backup/restore and offline physical inspection.

  Transactions:

  - Concurrent MVCC sessions.
  - Read committed, repeatable read and serializable isolation.
  - Atomic multi-row writes and index/catalog visibility.
  - Key and range locks, deadlock resolution and conflict handling.
  - Statement rollback and nested named savepoints.
  - Version reclamation, vacuum and bounded version-pressure admission.

  Relational and SQL:

  - Transactional tables, columns, indexes, views, sequences and identities.
  - BIGINT, nullable values and compact VARCHAR(7).
  - Defaults, generated identities, NOT NULL, CHECK, UNIQUE and foreign keys.
  - Multi-row insert, update and delete.
  - Indexed and unindexed predicates, ranges, IN, OR and conjunctions.
  - Inner and left joins.
  - Sorting, disk spill/merge, DISTINCT, grouping, HAVING, counts, sums and extrema.
  - Derived tables, scalar subqueries, EXISTS, membership and correlated nested queries.
  - EXPLAIN and EXPLAIN ANALYZE.


## Build and validation

JDK 25 is required. Run the complete initial-phase validation locally:

```sh
./verify
```

The command uses the checksum-pinned Gradle wrapper and an isolated
repository-local Gradle home, then runs a clean compile, static source policy,
module dependency checks, and all tests. 
