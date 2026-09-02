# River v1 JDBC support matrix

Status: accepted U03 contract

This matrix defines the bounded JDBC surface for River's v1 SQL types. It is
the support gate for U03, not a claim that every optional JDBC conversion is
implemented. Conversions not listed as supported fail with SQLSTATE `0A000`.
River creates `SQLException` objects only at this public adapter boundary.

## Prepared parameters

| SQL type | Supported setters and Java values | Typed `NULL` | Deliberately rejected |
| --- | --- | --- | --- |
| `BIGINT` | `setByte`, `setShort`, `setInt`, `setLong`; `setObject` with `Byte`, `Short`, `Integer`, or `Long` | integer JDBC types | floating-point and decimal objects |
| `BOOLEAN` | `setBoolean`; `setObject(Boolean)` | `BOOLEAN` or `BIT` | numeric and text objects |
| `DECIMAL(p,s)` | `setBigDecimal`; `setObject(BigDecimal)`; an exactly representable negative Java scale is normalized to scale zero | `DECIMAL` or `NUMERIC` | floating-point objects and values exceeding River's 18-digit bound |
| `VARCHAR(n)` | `setString`; `setObject(String)`; strict UTF-8, at most 255 Unicode scalar values | `VARCHAR` or `CHAR` | byte arrays, streams, readers, and implicit text parsing into other families |
| `DATE` | `setDate`; `setObject` with `Date` or `LocalDate` | `DATE` | calendar-relative and cross-family temporal conversion |
| `TIME(p)` | `setTime`; `setObject` with `Time` or `LocalTime` | `TIME` | calendar-relative conversion and nanoseconds not exactly representable as microseconds |
| local `TIMESTAMP(p)` | `setTimestamp`; `setObject` with `Timestamp` or `LocalDateTime` | `TIMESTAMP` | calendar-relative and implicit zoned conversion |
| `TIMESTAMP(p) WITH TIME ZONE` | `setObject` with `OffsetDateTime` or `Instant`; values are normalized to a UTC instant | `TIMESTAMP_WITH_TIMEZONE` | `Timestamp`, local date-time, offsets beyond River's bound, and sub-microsecond values |

`setObject(null)` supplies a context-inferred SQL `NULL`; `setNull` and typed
`setObject(null, type)` preserve the declared family. A typed `NULL` must be
compatible with its mutation target or membership operand. Calendar overloads,
floating-point, binary, LOB, national-character, array, row-ID, SQLXML, URL,
stream, and reader setters are unsupported. A nonzero scale argument to the
JDBC `setObject` scale overload is unsupported.

Markers are positional and bounded to 512 values and 16 KiB of UTF-8 parameter
text. They are supported in direct outer data statements, including an outer
statement flattened over a stored projection-only view. Stored definitions and
explicit derived, nested, correlated, and subquery marker topologies are
unsupported. A marker is not interpolated into SQL text. Unset or excess values
fail with `07001`.

## Result conversions

| SQL type | Preferred `getObject()` value | Supported direct and typed access | Rejected conversions |
| --- | --- | --- | --- |
| `BIGINT` | `Long` | integral getters, `getBoolean`, `getFloat`, `getDouble`, `getBigDecimal`, `getString`; typed `Long`, `Integer`, `BigDecimal`, or `String` | values outside a narrower integral getter's range fail rather than wrap |
| `BOOLEAN` | `Boolean` | `getBoolean`; numeric getters expose `0` or `1`; canonical `getString` is `false` or `true`; typed `Boolean`, `Long`, `Integer`, `BigDecimal`, or `String` | other object targets |
| `DECIMAL(p,s)` | scale-preserving `BigDecimal` | `getBigDecimal`, canonical `getString`; typed `BigDecimal` or `String` | primitive numeric and Boolean getters, plus typed integral/Boolean targets, fail with `0A000`; the internal scaled integer is never exposed |
| `VARCHAR(n)` | `String` | `getString`; typed `String` | numeric parsing is not performed; numeric getters and non-String object targets fail with `0A000` |
| `DATE` | `LocalDate` | `getDate`, `getString`; typed `LocalDate`, `Date`, or `String` | numeric, time, timestamp, and other object targets |
| `TIME(p)` | `LocalTime` | `getTime`, `getString`; typed `LocalTime`, `Time`, or `String` | numeric and other temporal/object targets; `java.sql.Time` is a seconds-only compatibility view while `LocalTime` retains microseconds |
| local `TIMESTAMP(p)` | `LocalDateTime` | `getTimestamp`, `getString`; typed `LocalDateTime`, `Timestamp`, or `String` | numeric, date/time-only, and zoned object targets |
| `TIMESTAMP(p) WITH TIME ZONE` | UTC `OffsetDateTime` | `getTimestamp`, canonical UTC `getString`; typed `OffsetDateTime`, `Timestamp`, or `String` | numeric, local temporal, `Instant`, and other object targets |

For a supported primitive conversion, SQL `NULL` returns the JDBC primitive
zero value and `wasNull()` is true. Supported object/string conversions return
Java null and set `wasNull()`. A conversion classified as unsupported with
`0A000` remains unsupported even when the row value is SQL `NULL`; a null value
does not expand the type matrix. Calendar result overloads are unsupported
because River local temporal values do not acquire an implicit zone. Every
listed query-result getter supports both its column-index and column-label
overload.

## Metadata and statement behavior

- `ResultSet` is forward-only, read-only, fetch-size one, and closes cursors at
  commit. Query values stream from the server without JDBC row buffering.
- `ResultSetMetaData` reports canonical JDBC type codes and names, declared
  character length, decimal precision/scale, temporal precision, display size,
  preferred Java class, and exact bound-projection nullability.
- `DatabaseMetaData` supports product/driver/transaction information and
  `getTables`, `getTableTypes`, `getColumns`, `getPrimaryKeys`, and
  `getIndexInfo`. Default and identity details that are not carried are reported
  as unknown rather than synthesized.
- Generated keys are supported for the admitted identity/sequence mutation
  path. Statement and prepared batches are bounded to 64 entries and report the
  exact successful prefix in `BatchUpdateException`.
- Connection, statement, and result warnings are currently empty:
  `getWarnings()` returns null and `clearWarnings()` is a no-op while open.
  Closed resources return `08003`.
- Callable statements, server-side prepared-plan caching, scrollable/updatable
  results, parameter metadata, multiple simultaneous statements per connection,
  and username/password connection properties are unsupported.

## Failure mapping

| Condition | SQLSTATE |
| --- | --- |
| Missing or excess parameter | `07001` |
| Closed JDBC resource | `08003` |
| Unsupported conversion or JDBC feature | `0A000` |
| Invalid parameter or column index | `22000` |
| String truncation | `22001` |
| Numeric range overflow | `22003` |
| Temporal precision/domain overflow | `22008` |
| Invalid time-zone displacement | `22009` |
| Bound SQL-family mismatch | `42804` |
| Resource or batch capacity exhausted | `53000` |

The principal real-path evidence is
`RiverTypedParameterJdbcTest`, `RiverExactTypeJdbcTest`,
`RiverTemporalJdbcTest`, `RiverDriverTest`, and
`SecureRemoteJdbcGateTest`. `RiverTypedParameterJdbcTest` and
`RiverSqlMainTest` carry every admitted family through the real TLS/token boundary.
Protocol domain and cross-frame validation remain owned by the protocol/client
suites; U06 owns the combined recovery, backup, and fault-injection fixture.
