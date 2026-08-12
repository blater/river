# River

River is a relational database implemented in Java. Its target is a
high-performance, crash-safe single-node database with SQL and JDBC access,
followed by a replicated journal and operational failover.

### Current capabilities

  - Recoverable heap and multi-level B+tree storage, WAL, checkpoints, torn-page repair and physical inspection.
  - Concurrent MVCC transactions, read-committed and serializable execution, key/range locking, deadlock handling,
    savepoints, group commit and version reclamation.
  - Transactional tables, indexes, columns, sequences, identities and views.
  - BIGINT, compact VARCHAR(7), NULLs, defaults and generated identity keys.
  - Primary, duplicate secondary, unique and nullable indexes.
  - NOT NULL, CHECK, UNIQUE and foreign-key constraints.
  - Multi-row insert, update and delete.
  - Comparisons, ranges, IN, OR, predicate conjunctions and NULL semantics.
  - Sorting, disk spill/merge, DISTINCT, aggregates, grouping and HAVING.
  - Indexed and unindexed inner joins plus left outer joins.
  - Derived, scalar, EXISTS, membership and correlated subqueries with bounded deep nesting.
  - Executable plans, EXPLAIN and EXPLAIN ANALYZE.
  - Embedded API, TLS/token-authenticated server, bounded concurrent sessions, streaming client and JDBC.
  - Prepared parameters, batching, generated keys and JDBC metadata subset.
  - Remote SQL CLI.
  - Quiescent backup/restore and offline corruption-aware inspection.

## Build and validation

JDK 25 is required. Run the complete initial-phase validation locally:

```sh
./verify
```

The command uses the checksum-pinned Gradle wrapper and an isolated
repository-local Gradle home, then runs a clean compile, static source policy,
module dependency checks, and all tests. 

