# River

River is a single-node relational database written in Java. It provides an
embedded Java API, JDBC access, and a command-line client. Its storage engine
uses MVCC, heap pages, B+trees, a write-ahead log, and checkpoints.

> River 0.1.0-alpha.2 is an evaluation release. It is incomplete and may break.
> Read the [release limits](docs/delivery/alpha-2-known-limitations.md) before
> using it with important data.

## What works

### Storage and recovery

- Durable heap and B+tree storage with unique, non-unique, and nullable
  indexes.
- Write-ahead logging, group commit, checkpoints, WAL rotation, committed-WAL
  recovery, and torn checkpoint-page repair.
- Quiescent backup and restore, and offline physical inspection.

### Transactions

- Concurrent MVCC sessions with read-committed, repeatable-read, and
  serializable isolation.
- Statements and explicit transactions publish DML and catalog changes
  atomically.
- Key and range locks, deadlock resolution, statement rollback, and nested
  named savepoints.

### SQL and clients

- Tables, indexes, views, sequences, identities, defaults, `NOT NULL`,
  `CHECK`, `UNIQUE`, and foreign keys.
- `SMALLINT`, `INTEGER`, `BIGINT`, `DECIMAL(p,s)`, `REAL`, `DOUBLE PRECISION`,
  `BOOLEAN`, `VARCHAR(n)`, `DATE`, `TIME(p)`, local `TIMESTAMP(p)`, and
  `TIMESTAMP(p) WITH TIME ZONE`.
- Multi-row `INSERT`, `UPDATE`, and `DELETE`; indexed and scanned predicates;
  scalar expressions; and SQL three-valued logic.
- Two-to-64-role `INNER` and `LEFT` joins with bounded nested-loop, hash,
  and merge strategies.
- Aggregation, `GROUP BY`, `HAVING`, `DISTINCT`, ordering, limits, and bounded
  disk spill.
- Derived tables and bounded scalar, `EXISTS`, `IN`, `NOT IN`, and correlated
  subqueries. They can feed projections, aggregates, grouping, ordering,
  joins, and outer derived-table stages.
- `ANALYZE`, `EXPLAIN`, and `EXPLAIN ANALYZE` with durable statistics and
  execution counters.
- Streaming JDBC 4.3 results and prepared parameters. Loopback clients may use
  plain transport or TLS 1.3 with token authentication.

The [SQL conformance profile](docs/compatibility/sql-conformance-profile.md)
defines the exact SQL grammar and semantics. The
[JDBC support matrix](docs/compatibility/jdbc-support-matrix.md) lists supported
conversions, metadata, SQLSTATEs, and deliberate omissions.

## Current limitations in alpha.2

| Area | Current limit |
| --- | --- |
| Table and result columns | 1,024 table columns; 1,664 result/group/order lanes |
| Encoded table row | 8,192 bytes |
| Indexed-table capacity | The legacy 65,536 row/version ceiling is removed. Disk-backed row-location and version directories, scalable checkpoint metadata, and a bounded pinned page cache support positive logical row IDs through 4,294,967,294 without resident per-row state. Physical page IDs remain positive `int`s, operation/WAL bounds remain explicit, and this is capacity/recovery evidence—not a TPC-C throughput claim |
| Text | `VARCHAR(n)`, `1 <= n <= 255`; at most 1,020 encoded bytes per value |
| Join shape | 2–64 left-associative roles |
| Materialized query stores | 65,536 rows and 256 MB per bounded store |
| Network | Loopback only; authenticated access uses TLS 1.3 and a token |
| JDBC | One live statement per connection; forward-only, read-only results |
| Operations | Offline backup; no replication, failover, or online migration |

## Build and run

### Size configuration convention

User-facing River size values use standard `KB`, `MB`, and `GB` units. Binary
unit suffixes are not used. Exact byte values remain an implementation detail
for page and format invariants. See
[ADR 0013](docs/adr/0013-configuration-size-units.md).

River requires JDK 25. Gradle verifies dependency checksums.

Build all module JARs and the CLI distribution:

```sh
./gradlew assemble
```

River does not yet ship a standalone server service. A host application opens
the database through `EmbeddedRiver` and starts `LoopbackRiverServer`. The
[database how-to](HOWTO.md) gives the lifecycle code and shutdown rules.

After starting a plain loopback server, install and run the SQL client:

```sh
./gradlew :river-cli:installDist
river-cli/build/install/river-cli/bin/river-cli 9191 < setup.sql
```

The CLI reads semicolon-terminated SQL, emits tab-separated rows, and stops at
the first error. See the [CLI reference](river-cli/README.md) for TLS and token
authentication.

## Validate a checkout

Run the ordinary test matrix while developing:

```sh
./gradlew test
```

Run the clean, reproducible release check at an integration checkpoint:

```sh
./verify
```

`./verify` rebuilds reproducible archives, runs `clean check`, enforces source
and dependency policies, and uses an isolated repository-local Gradle home by
default.

## License

River uses the [GNU Affero General Public License v3](LICENSE).
