# River

River is a relational database written in Java, with an embedded API, JDBC
access, and a command-line client. Its storage engine uses MVCC, heap pages,
B+trees, a write-ahead log, and checkpoints.

The project is also an experiment in building a large system with coding
agents: how to specify work, divide it between agents, and review the result.

> River is pre-V1 evaluation software. APIs and on-disk formats may change
> incompatibly; automatic upgrades are not provided. This README describes
> the `riverd` feature candidate as of 2026-09-08. The
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
supported features and omissions. Some numeric limits in those documents
predate the current implementation; the table below reflects current source.

## Current limits

Memory, temporary disk space, and concurrent use can limit an operation before
it reaches these bounds. A supported row-ID range does not mean River has been
tested with that many rows.

| Area | Limit |
| --- | --- |
| Columns | 1,024 per table; 1,664 result columns, grouping expressions, or ordering expressions. Table rows must also fit the byte limit below |
| Stored row | 16,216 encoded bytes, including row metadata, within a 16,384-byte page |
| Text | `VARCHAR(n)` supports declarations up to 65,535 Unicode scalar values. A table schema must fit the stored-row limit, allowing four bytes per declared character plus metadata; this gives table columns a lower practical limit |
| Decimal | Up to 38 digits, with scale from zero to the declared precision |
| Indexes | Up to 64 secondary indexes per table, 32 columns per key, and 3,072 encoded user-key bytes |
| Table capacity | Logical row IDs from 1 through 4,294,967,294. Row and version directories are stored on disk; physical page IDs and WAL operation sizes have separate limits |
| Joins | Up to 64 table references in a left-associative `INNER`/`LEFT` chain. Join reordering and partitioned hash spill remain unfinished |
| Materialized results | Paged storage and external sorting replace the old 65,536-row / 256 MB store cap. Capacity depends on memory budgets, temporary disk space, and address limits. Each encoded result-row payload is limited to 4,194,304 bytes |
| Savepoints | Limited by the session's resource budget; the old three-savepoint cap is gone. Budget exhaustion returns `RESOURCE_EXHAUSTED` |
| Network | Loopback only, with TLS 1.3 and token authentication. Remote deployment is unsupported |
| JDBC | One live statement per connection; forward-only, read-only results. No callable statements or scrollable/updatable cursors |

The main sources for these limits are
[`SqlShapeLimits`](river-base/src/main/java/io/riverdb/base/sql/SqlShapeLimits.java),
[`SqlTypeDescriptor`](river-base/src/main/java/io/riverdb/base/type/SqlTypeDescriptor.java),
[`HeapPage`](river-storage/src/main/java/io/riverdb/storage/heap/HeapPage.java), and
[`IndexedTableLimits`](river-engine/src/main/java/io/riverdb/engine/table/IndexedTableLimits.java).

## What remains unfinished

The current `riverd start` candidate workflow has been validated on
macOS/APFS and Linux/ext4/XFS. Windows/NTFS validation and the final audit
acceptance decision remain pending. The foreground server creates or reopens
the database, publishes `security/client.properties`, and accepts only
authenticated TLS 1.3 connections. Backup and restore are offline; replication,
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

The build uses a JDK 25 toolchain and targets Java 25. Gradle verifies dependency
checksums. Always use `--no-daemon` and run one build or database workload at a
time on the host.

Build the server and SQL client distributions:

```sh
./gradlew --no-daemon :river-server-app:installDist :river-cli:installDist
```

Start the candidate server in the foreground. It writes the
generated client configuration under its data directory:

```sh
river-server-app/build/install/riverd/bin/riverd start --datadir=/absolute/path/to/database --port=9191
```

Use the reported `security/client.properties` path with the SQL client or JDBC:

```sh
river-cli/build/install/river-cli/bin/river-cli /absolute/path/to/database/security/client.properties < setup.sql
```

The CLI reads semicolon-terminated SQL, emits tab-separated rows, and stops at
the first error. The [CLI reference](river-cli/README.md) and [database
how-to](HOWTO.md) describe the generated client-file workflow.

Size settings use decimal `KB`, `MB`, and `GB`. The exact byte counts in the
limits table describe storage formats. See
[ADR 0013](docs/adr/0013-configuration-size-units.md).

## Validate a checkout

Use focused module and test tasks while editing. Run the full test matrix at
an integration checkpoint:

```sh
./gradlew --no-daemon test
```

For the clean release checks:

```sh
./verify
```

`./verify` checks reproducible archives, runs `clean check`, and enforces source
and dependency policies. It uses an isolated Gradle home in the repository by
default.

The candidate clean test run reports 1,897 tests: 1,879 executed, 18 skipped,
and no failures or errors. The module graph check passes; existing source-policy
and SQL-shape violations still fail their checks. This run did not validate
every additional release check. Details are in the
[checkpoint ledger](docs/performance-checkpoints.md).

For River-specific TPS diagnostics, run `./make.sh` first, then
`tools/tps-test.sh`. `make.sh` runs `:river-bench:installTps` to build the
runnable benchmark distribution;
the TPS command consumes that distribution and does not run Gradle or inspect
the source tree. The default test version is the current Git branch. When a
branch contains several meaningful variants, pass
`--version=<meaningful-variation>` and record that value in the TPS log or
artifact. Follow the [performance loop](AGENTS.md#tpc-c-performance-loop)
when choosing workloads and collecting evidence.

## Direction and backlog

The first goal is a capable relational database. Later work may add storage
and queries across different data types to help agents manage context. NQL
integration may also provide queries and updates across databases and files.
These are future directions, not current features.

Work is tracked in Markdown [tickets](docs/tickets/), configured by
[`ticket.yaml`](ticket.yaml). Run `tk` from the repository or a subdirectory.
The [delivery queue](docs/backlog-kanban.md) sets the current order of work.
[`manifesto.md`](manifesto.md) and [`AGENTS.md`](AGENTS.md) define the engineering
principles and working rules. Tickets link to design decisions, evidence, and
work owned by other repositories.

## License

River uses the [GNU Affero General Public License v3](LICENSE).
