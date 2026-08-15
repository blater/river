# River JDBC slice

The pre-V1 driver accepts `jdbc:river://localhost:PORT` and uses the production
River client, protocol, server, engine, WAL, and storage path. It currently
supports one statement per connection, auto-commit or explicit repeatable-read
and serializable transactions, update counts, and streaming forward-only,
read-only typed result sets. Each `next()` consumes one
protocol fetch credit;
the driver does not buffer the result set. Primitive `next()`/`getLong()` use
reusable row carriers and do not create per-row JDBC adapter objects.
`COUNT(*)` is exposed as one streamed row. `ORDER BY` uses an existing ordered
access path when possible and otherwise a bounded materialized sort/spill path.
`SELECT column, COUNT(*) ... GROUP BY column` likewise streams consecutive
groups from primary-key or secondary-index order without a hash table.
Qualified inner equijoins stream the outer table and probe an inner primary or
unique index for each row. Missing inner rows are skipped; joins that would
require an unbounded hash build are rejected.

`PreparedStatement` sends bounded positional BIGINT, BOOLEAN, DECIMAL,
VARCHAR, DATE, TIME, local TIMESTAMP, and zoned TIMESTAMP parameters separately
from SQL text. Batches retain at most 64 proportional typed snapshots.
The parameter carrier is borrowed only for synchronous admission and reused
afterward; user values are never interpolated into SQL text. Parameter count,
SQL length, and retained text bytes are bounded at statement creation or bind.

Statement batches hold at most 64 SQL snapshots, while prepared batches own at
most 64 proportional typed snapshots. They execute in order through the same
transaction state and report the exact successful prefix in
`BatchUpdateException` if an entry fails.

The authoritative bounded v1 conversion contract, including rejected
conversions, NULL behavior, SQLSTATEs, metadata, batching, and warnings, is the
[JDBC support matrix](../docs/compatibility/jdbc-support-matrix.md). Its compact
preferred-mapping summary is:

| SQL family | Prepared setters | Result access |
| --- | --- | --- |
| `BIGINT` | integral setters, `setObject` | `getLong`, `getInt`, `Long` |
| `BOOLEAN` | `setBoolean`, `setObject` | `getBoolean`, `Boolean` |
| `DECIMAL(p,s)` | `setBigDecimal`, `setObject` | `getBigDecimal`, `BigDecimal` |
| `VARCHAR(n)` | `setString`, `setObject` | `getString`, `String` |
| `DATE` | `setDate`, `LocalDate` | `getDate`, `LocalDate` |
| `TIME(p)` | `setTime`, `LocalTime` | `getTime`, `LocalTime` |
| local `TIMESTAMP(p)` | `setTimestamp`, `LocalDateTime` | `getTimestamp`, `LocalDateTime` |
| zoned `TIMESTAMP(p)` | `OffsetDateTime`, `Instant` | `getTimestamp`, `OffsetDateTime` |

Primitive numeric and integral object conversions from `DECIMAL` are
deliberately rejected with `0A000`; `BigDecimal` and canonical string access
preserve its scale, and River never exposes the internal scaled integer.

`setNull` carries a declared family and Java null setters use a bounded SQL
NULL. Incompatible types are `42804`, missing/excess parameters are `07001`,
while an out-of-range setter index is `22000`. Text truncation is `22001`,
decimal overflow is `22003`, temporal precision or
domain overflow is `22008`, invalid offsets are `22009`, and unsupported JDBC
families remain `0A000`. Generated keys, successful-prefix batches, typed
metadata, and Java-time plus `java.sql` compatibility accessors are supported.
Connection, statement, and result warnings are currently empty: `getWarnings`
returns null and `clearWarnings` is a no-op while open; closed resources report
`08003`.

`RiverDataSource` supports both plain loopback connections and the production
TLS 1.3 token-authenticated client path. It owns a private token copy, snapshots
that copy per connection, and erases both snapshots and retained credentials.
Username/password connection overloads remain unsupported; River tokens are
high-entropy credentials, not human passwords.

The audited server path binds that token to a configured service-principal
permission mask. Authorization denial is reported as SQLSTATE `42501`; audit
capacity exhaustion is `53000`. `Statement.cancel()` deliberately fences and
closes the ordered connection so a blocked request unwinds on both peers, and
`Connection.abort(Executor)` uses the same transport cancellation. Any open
remote transaction is rolled back when the server observes the disconnect.

Projection names, column count, and typed metadata are available when the query
opens. Server-side prepared plans, floating-point/binary/LOB parameters,
callable statements, non-loopback URLs, and authenticated JDBC properties
remain unsupported until their production consumers are implemented.
`Connection.getMetaData()` truthfully reports the product, driver, JDBC version,
transaction levels, result-set shape, identifier limits, and batching support.
`getTables`, `getTableTypes`, `getColumns`, `getPrimaryKeys`, and `getIndexInfo`
expose durable tables, views, and ready indexes;
column names, order, aliases, and type descriptors come from the SQL binder,
query and catalog-column nullability is exact, while unsupported default and
identity details are reported as unknown. Secure JDBC configuration is currently
provided by `RiverDataSource`.

River uses status returns internally. JDBC-mandated `SQLException` objects are
created only at this external adapter boundary.
