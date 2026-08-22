# River

River is a relational database implemented in Java. Its target is a
high-performance, crash-safe single-node database with SQL and JDBC access,
followed by a replicated journal and operational failover.

> **Alpha:** River 0.1.0-alpha.2 is an early, pre-V1 release for evaluation.
> Its APIs and durable formats may change without compatibility adapters. Read
> the [known limitations](docs/delivery/alpha-2-known-limitations.md) before
> using it with important data.

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
  - BIGINT, BOOLEAN, DECIMAL, local and zoned temporal types, nullable values,
    and bounded UTF-8 VARCHAR.
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
