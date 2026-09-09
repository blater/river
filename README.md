# River

River is a relational database written in Java, with an embedded API, JDBC
access, and a command-line client. Its storage engine uses MVCC, heap pages,
B+trees, a write-ahead log, and checkpoints.

The project is also an experiment in building a large system with coding
agents: how to specify work, divide it between agents, and review the result.

> River is pre-V1 evaluation software. APIs and on-disk formats may change
> incompatibly; automatic upgrades are not provided. This README describes
> the `river` feature candidate as of 2026-09-08. The
> [alpha.2 release notes](docs/delivery/alpha-2-known-limitations.md) describe
> the earlier release.

## What works

### Storage and transactions

- Durable heap and B+tree storage, with unique and non-unique indexes.
- Write-ahead logging, group commit, checkpoints, WAL rotation, recovery of
  committed transactions, and repair of torn checkpoint pages.
- Concurrent MVCC sessions with read-committed, repeatable-read, and
  serializable isolation.
- Atomic data and catalog changes, key and range locks, deadlock resolution,
  statement rollback, and named savepoints.
- Offline backup, restore, and physical file inspection.

### SQL and clients

- Tables, composite keys and indexes, views, sequences, identities, defaults,
  `NOT NULL`, `CHECK`, `UNIQUE`, and foreign keys.
- Integer and floating-point types, `DECIMAL(p,s)` up to 38 digits, `BOOLEAN`,
  `VARCHAR(n)`, `DATE`, `TIME(p)`, and timestamps with or without time zones.
- Multi-row `INSERT`, `UPDATE`, and `DELETE`; expressions and SQL
  three-valued logic.
- `INNER` and `LEFT` joins using nested-loop, hash, and merge strategies.
- Aggregation, `GROUP BY`, `HAVING`, `DISTINCT`, ordering, limits, and disk spill.
- `UNION` and `UNION ALL`, including parenthesized expressions and result
  ordering and limits.
- Derived tables and scalar, `EXISTS`, `IN`, `NOT IN`, and correlated subqueries
  within the supported SQL shapes.
- `ANALYZE`, `EXPLAIN`, and `EXPLAIN ANALYZE`, with stored statistics and
  execution counters.
- Streaming JDBC 4.3 results and prepared parameters. Installed clients use the
  generated TLS 1.3 and token-authenticated `client.properties` configuration.
  Embedded applications may use the embedded API directly.

The [SQL conformance profile](docs/compatibility/sql-conformance-profile.md)
and [JDBC support matrix](docs/compatibility/jdbc-support-matrix.md) describe
supported features and omissions. The main size and capacity limits are
summarized below.

## Current limits

These limits apply to individual tables, rows, keys, or SQL clauses as stated.
Available memory, temporary disk space, and concurrent work may limit an
operation sooner. Capacity limits describe what the formats can represent;
they are not claims that River has been tested at that scale.

**The 4 MiB result-row limit applies to one row, not the whole query result.**
A query can return more than 4 MiB in total. JDBC streams rows as the client
reads them; sorting and other operations that retain results also need memory
and, when they spill to disk, temporary disk space.

| Area | Current limit |
| --- | --- |
| Table columns | Up to 1,024 columns per table. The combined row must also fit the stored-row limit below |
| Result columns and SQL clauses | Up to 1,664 columns in a result row, 1,664 expressions in a `GROUP BY` list, and 1,664 expressions in an `ORDER BY` list |
| One stored row | Up to 16,216 bytes after encoding, including row metadata. A stored row must fit within one 16,384-byte page |
| Text (`VARCHAR(n)`) | The type can represent `n` up to 65,535 Unicode scalar values (not bytes). A table definition must fit the stored-row limit using each column's declared maximum: four bytes per scalar value, plus metadata. This makes the maximum `n` for a stored column smaller, even if its actual values would be short |
| Decimal (`DECIMAL(p,s)`) | Up to 38 total digits (`p`). The number of digits after the decimal point (`s`) must be between zero and `p` |
| Indexes and keys | Up to 64 secondary indexes per table and 32 columns per key. The combined encoded column values in one index key must fit within 3,072 bytes |
| Table row IDs | Row IDs range from 1 through 4,294,967,294 per table. This is the ID range, not a guaranteed usable table size; storage capacity and page-address limits also apply |
| Joins | Up to 64 table references in one left-to-right chain of `INNER`/`LEFT` joins. Referencing the same table twice counts twice. Join reordering and partitioned hash-join spill are unfinished |
| One result row | Up to 4 MiB (4,194,304 bytes) of encoded row payload. This is a per-row limit, not a limit on the total query result |
| Results held for sorting or other processing | Stored in pages and able to spill to temporary disk. Total capacity depends on configured memory budgets, temporary disk space, and address limits |
| Savepoints | The number of savepoints is limited by the session's resource budget. Exhausting that budget returns `RESOURCE_EXHAUSTED` |
| Network access | Clients must connect from the same machine over a loopback address. Remote deployment is unsupported |
| JDBC | Multiple statements may be open per connection, but only one query can be active at a time. Finish reading or close that query before running another. Result sets are forward-only and read-only: they cannot scroll backward or update rows. Callable statements are unsupported |

The main sources for these limits are
[`SqlShapeLimits`](river-base/src/main/java/io/riverdb/base/sql/SqlShapeLimits.java),
[`SqlTypeDescriptor`](river-base/src/main/java/io/riverdb/base/type/SqlTypeDescriptor.java),
[`HeapPage`](river-storage/src/main/java/io/riverdb/storage/heap/HeapPage.java), and
[`IndexedTableLimits`](river-engine/src/main/java/io/riverdb/engine/table/IndexedTableLimits.java).

## What remains unfinished

The current `river server start` candidate workflow has been validated on
macOS/APFS and Linux/ext4/XFS. Windows/NTFS validation remains pending. SQL/
security audit collection is deferred pending a concrete performance-neutral
design. The foreground server creates or reopens
the database, publishes `security/client.properties`, and accepts only
authenticated connections. Backup and restore are offline; replication,
failover, and online schema migration are not available.

Focused recovery, concurrency, and capacity tests pass, but the full crash,
isolation, fault-injection, and long-running growth tests required for release
are unfinished. Billion-row workloads have not been qualified.

TPC-C-derived workloads run through the diagnostic tools. Correctness and
scaling revalidation is still open, followed by further throughput work. Local
samples are not audited TPC-C results and must not be reported as `tpmC`.
The [performance checkpoints](docs/performance-checkpoints.md) record results
and their limits; the [delivery queue](docs/backlog-kanban.md) lists the work
still required for performance and cross-database comparisons.

## Build and run

Building River requires JDK 25. Gradle verifies dependency checksums.

Build the River distribution:

```sh
./gradlew :river-server-app:installDist
```

Start the candidate server in the foreground. It writes the
generated client configuration under its data directory:

```sh
river-server-app/build/install/river/bin/river server start --datadir=/absolute/path/to/database --port=9191
```

Use the reported `security/client.properties` path with the SQL client or JDBC:

```sh
river-server-app/build/install/river/bin/river /absolute/path/to/database/security/client.properties < setup.sql
```

The CLI reads semicolon-terminated SQL, emits tab-separated rows, and stops at
the first error. The [CLI reference](river-cli/README.md) and [database
how-to](HOWTO.md) describe the generated client-file workflow.

## Validate a checkout

Run the tests:

```sh
./gradlew test
```

For the clean release checks:

```sh
./verify
```

`./verify` runs `clean check`: tests plus source and dependency policy checks.

For River-specific throughput diagnostics:

```sh
./make.sh
tools/tps-test.sh
```

Results use the current Git branch as their version label. Use
`--version=<label>` to give a run a different name.

## Direction and backlog

The first goal is a capable relational database. Later work may add storage
and queries across different data types to help agents manage context. NQL
integration may also provide queries and updates across databases and files.
These are future directions, not current features.

The [delivery queue](docs/backlog-kanban.md) lists planned work, with details
in the [tickets](docs/tickets/). See the [manifesto](manifesto.md) for engineering
principles and [AGENTS.md](AGENTS.md) for contributor workflow instructions.

## License

River uses the [GNU Affero General Public License v3](LICENSE).
