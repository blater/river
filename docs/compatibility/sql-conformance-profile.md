# River SQL conformance profile

<!-- markdownlint-disable MD013 -->

Status: proposed M5 contract

This profile is the semantic authority for River's admitted pre-V1 SQL
surface. Parser acceptance is not support. A feature is supported only when
its SQL, catalog, durable row/index/WAL, embedded API, protocol, JDBC, recovery,
and error behavior satisfy the applicable fixtures.

## Current and target type surface

The current implementation supports `BIGINT` and a packed, printable-ASCII
`VARCHAR(7)`. That text representation is a bootstrap implementation, not the
M5 contract.

M5 admits the following scalar types:

| Type | Parameters | Representation and ordering |
| --- | --- | --- |
| `BIGINT` | none | Signed 64-bit integer, signed numeric order. |
| `BOOLEAN` | none | Boolean value; `NULL` remains separate. |
| `DECIMAL(p,s)` | `1 <= p <= 18`, `0 <= s <= p` | Signed scaled 64-bit integer; exact numeric order and checked arithmetic. |
| `VARCHAR(n)` | `1 <= n <= 255` | Strict UTF-8; `n` Unicode scalar values maximum; deterministic Unicode-code-point order. |
| `DATE` | none | Proleptic-Gregorian epoch day, years 0001-9999. |
| `TIME(p)` | `0 <= p <= 6`, default 6 | Microseconds since midnight; no time zone. |
| `TIMESTAMP(p)` | `0 <= p <= 6`, default 6 | Local date-time on a zone-free microsecond timeline. |
| `TIMESTAMP(p) WITH TIME ZONE` | `0 <= p <= 6`, default 6 | UTC epoch-microsecond instant; input zone identity is not retained. |

Floating point, `INTERVAL`, `TIME WITH TIME ZONE`, binary/LOB, UUID, JSON,
arrays, locale-sensitive collations, and retained per-value IANA zone identity
are outside M5.

## Text

- Character input and storage are strict UTF-8.
- Declared length counts Unicode scalar values, not UTF-16 code units or UTF-8
  bytes.
- One value is bounded to 1,020 encoded bytes. The declared worst-case table
  row, including row metadata, must fit River's 4 KiB row bound.
- V1 comparison is case-sensitive Unicode-code-point order.
- River performs no implicit Unicode normalization or locale-sensitive case
  folding. Canonically equivalent but differently encoded scalar sequences are
  distinct.
- Malformed UTF-8, unpaired surrogates, excessive scalar length, excessive byte
  length, and a schema whose worst-case row cannot fit are rejected before
  durable mutation.

## Exact numeric behavior

- Decimal scale is part of the declared type. Values are converted to the
  target scale only by the admitted cast/assignment rules.
- No implicit conversion loses a fractional part. The binder derives a stable
  result precision and scale for each admitted expression; division and an
  explicit scale-reducing cast use round-half-even.
- V1 implements unary `+`/`-`, binary `+`, `-`, `*`, `/`, and `%`, and the
  functions `ABS`, `CEIL`, `FLOOR`, `ROUND`, and `TRUNCATE`.
- V1 implements decimal `SUM`, `AVG`, `MIN`, and `MAX`. Accumulators use a
  reusable wide representation and fail before publication if the declared
  result cannot represent the final value.
- Arithmetic, aggregate accumulation, casts, default evaluation, and index-key
  construction check overflow before publication. Division by zero is never
  converted to `NULL`, infinity, or a warning.
- Comparison aligns scales exactly without rounding. `1.0` and `1.00` compare
  equal and therefore have identical equality, uniqueness, join, grouping,
  `DISTINCT`, ordering, `BETWEEN`, and index-range behavior.
- `BIGINT` may promote exactly to a compatible decimal expression. Decimal to
  `BIGINT` requires an explicit cast and fails if a fractional part would be
  discarded unless an explicit rounding function removed it first.
- Floating-point approximation is not used to parse, store, compare, or format
  an exact decimal.

Internally, admitted decimals use a signed scaled 64-bit value plus reusable
wide scratch arithmetic for rescaling and multiplication. `BigDecimal` is the
JDBC/public-boundary object mapping, not River's per-row execution object.

For operands `DECIMAL(p1,s1)` and `DECIMAL(p2,s2)`, let `i1 = p1 - s1` and
`i2 = p2 - s2`. River derives decimal result descriptors as follows before
applying the precision-18 bound:

| Operation | Natural integer digits | Natural scale |
| --- | ---: | ---: |
| `+`, `-` | `max(i1,i2) + 1` | `max(s1,s2)` |
| `*` | `i1 + i2` | `s1 + s2` |
| `/` | `i1 + s2` | `max(6,s1 + p2 + 1)` |
| `%` | `min(i1,i2)` | `max(s1,s2)` |

If natural precision exceeds 18, River retains the natural integer capacity
first and reduces fractional scale to fit; discarded fractional digits are
rounded half-even. If the natural integer capacity itself exceeds 18, the
descriptor is `DECIMAL(18,0)` and a runtime value outside that range fails with
`22003`. `ROUND` and `TRUNCATE` require a constant target scale in the bounded
range. `AVG` uses the division rule; `SUM` accumulates exactly in reusable wide
scratch and converts once to its declared result.

## Predicate and aggregate contexts

Every admitted scalar type uses one typed expression and comparison contract
in all supported contexts. Operator eligibility remains type-specific:

- projection and assignment;
- `WHERE` and `JOIN ... ON`;
- `HAVING`, including predicates over aggregate results;
- `CHECK` constraints;
- equality and `IN` for every comparable type;
- `<`, `<=`, `>`, `>=`, and `BETWEEN` for every ordered type;
- scalar and correlated-subquery predicates;
- `GROUP BY`, `DISTINCT`, and `ORDER BY`;
- uniqueness enforcement and index equality/range bounds; and
- `MIN`/`MAX`, plus type-specific aggregates such as decimal `SUM`/`AVG`.

All contexts use the same coercion, collation, comparison, overflow, and
three-valued NULL behavior. In particular, a `BETWEEN` predicate and the
equivalent pair of inclusive comparisons must select the same rows through an
index or a scan, and `HAVING` must compare a decimal or temporal aggregate with
the same kernel used by `WHERE`.

`BOOLEAN` supports `=`, `<>`, `IN`, `IS TRUE`, `IS FALSE`, and `IS UNKNOWN`,
but it has no ordering and is invalid in `BETWEEN`, `MIN`, or `MAX`. `BIGINT`,
`DECIMAL`, `VARCHAR`, `DATE`, `TIME`, and both timestamp types are ordered.

## Temporal parsing and formatting

River accepts locale-independent ASCII forms:

```text
DATE 'YYYY-MM-DD'
TIME 'HH:MM:SS[.ffffff]'
TIMESTAMP 'YYYY-MM-DD HH:MM:SS[.ffffff]'
TIMESTAMP WITH TIME ZONE 'YYYY-MM-DD HH:MM:SS[.ffffff]+HH:MM'
```

The parser is strict: components use the shown widths, fractional precision is
0-6 digits, and the complete input must be consumed. It does not normalize an
invalid field into a later date or time.

River rejects:

- year zero, years outside 0001-9999, and invalid Gregorian dates;
- hour 24, second 60, and other out-of-range time fields;
- a fractional value more precise than the declared target;
- a required but absent UTC offset;
- numeric offsets outside `-14:00` through `+14:00`; and
- locale names, abbreviations such as `BST`/`PST`, and trailing input.

Canonical SQL/JDBC string output uses the same date and time field order,
zero-padded fields, the declared fractional precision, and a numeric offset for
zoned timestamps. Parsing and formatting never depend on the process locale.

## Time zones and daylight-saving transitions

- Every session has a time zone; the default is UTC.
- Admitted session zones are UTC, a valid fixed numeric offset, or an IANA
  region ID from the supported JDK tzdb.
- `DATE`, `TIME`, and `TIMESTAMP` without time zone are local values. They do
  not inherit or carry the session zone.
- `TIMESTAMP WITH TIME ZONE` is an instant. Equality, ordering, uniqueness, and
  indexes compare the instant, regardless of the offset used at input.
- Converting a local timestamp to an instant uses an explicit zone when one is
  supplied, otherwise the session zone. A nonexistent DST-gap local time is an
  error. An ambiguous overlap local time is an error unless an explicit offset
  valid for that region and local time selects one occurrence.
- Converting an instant to local fields uses the requested or session zone and
  is unambiguous.
- Implicit assignment or comparison between zoned and unzoned timestamps is
  not supported.
- River exposes the runtime tzdb version. Supported deployments and future
  replica groups use one approved tzdb baseline until an explicit upgrade gate
  changes it.

## Current-time and replay semantics

`CURRENT_DATE`, `CURRENT_TIMESTAMP`, `LOCALTIME`, and
`LOCALTIMESTAMP` are evaluated once per statement. Every row affected by that
statement observes the same value.
Defaults and generated expressions capture that resolved value in the durable
transaction effect. Restart recovery, backup/restore, retry outcome lookup,
and future replication replay the captured value; they never consult a clock
or rerun zone resolution.

## Temporal functions, arithmetic, and comparison

M5 includes:

- `EXTRACT(YEAR|MONTH|DAY FROM date-or-timestamp)`;
- `EXTRACT(HOUR|MINUTE|SECOND FROM time-or-timestamp)`;
- `EXTRACT(TIMEZONE_HOUR|TIMEZONE_MINUTE FROM zoned-timestamp)`;
- `date + bigint-days`, `date - bigint-days`, and `date - date`, with the last
  form returning an exact `BIGINT` day count;
- strict casts between admitted temporal types and their canonical `VARCHAR`
  representation; and
- `local-timestamp AT TIME ZONE zone` and
  `zoned-timestamp AT TIME ZONE zone` using the gap/overlap rules above.

Every temporal type supports `=`, `<>`, `<`, `<=`, `>`, `>=`, `BETWEEN`,
`MIN`, `MAX`, ordering, grouping, `DISTINCT`, uniqueness, joins, and index
ranges. Like local types compare on their local chronology. Zoned timestamps
compare by instant. Comparisons across `DATE`, `TIME`, local `TIMESTAMP`, and
zoned `TIMESTAMP` families require an explicit admitted cast. The engine never
applies the session zone implicitly to make two unlike temporal values
comparable.

Month/year arithmetic and timestamp-duration arithmetic wait for the deferred
`INTERVAL` profile; v1 does not invent end-of-month behavior in ad hoc date
functions. SQL-standard `CURRENT_TIME` waits for `TIME WITH TIME ZONE`;
`LOCALTIME` supplies the session-local unzoned wall time in M5.

## JDBC mappings

| SQL type | Preferred JDBC 4.2 object |
| --- | --- |
| `BIGINT` | `Long` |
| `BOOLEAN` | `Boolean` |
| `DECIMAL` | `BigDecimal` |
| `VARCHAR` | `String` |
| `DATE` | `LocalDate` |
| `TIME` | `LocalTime` |
| `TIMESTAMP` | `LocalDateTime` |
| `TIMESTAMP WITH TIME ZONE` | `OffsetDateTime` |

Applicable `java.sql.Date`, `Time`, and `Timestamp` accessors remain supported
compatibility views. JDBC metadata reports the declared character length,
decimal precision/scale, temporal precision, nullability, and the correct JDBC
type code. The protocol transports versioned type tags and binary values; it
does not use formatted text as River's internal typed-value representation.

## Required SQLSTATEs

| Condition | SQLSTATE |
| --- | --- |
| String data too long | `22001` |
| Numeric value out of range | `22003` |
| Division by zero | `22012` |
| Invalid datetime format | `22007` |
| Datetime field overflow | `22008` |
| Invalid time-zone displacement | `22009` |
| Unsupported type, cast, or temporal feature | `0A000` |

An expected parse, conversion, range, or zone error returns River status at
internal boundaries and becomes `SQLException` only at JDBC.

## M5 acceptance

M5 requires fixtures for minimum/maximum values, nulls, ordering, uniqueness,
casts, defaults, constraints, every admitted comparison, `BETWEEN`, `IN`,
`WHERE`, joins, grouping, `HAVING`, distinct, correlated predicates, prepared
parameters, result metadata, UTF-8 boundaries, decimal math/divide-by-zero/
overflow, leap years, fractional precision, UTC limits, DST gaps/overlaps,
session-zone changes, statement-stable current values, checkpoint/reopen,
backup/restore, corrupt durable tags, and authenticated JDBC round trips. Hot
execution must not allocate per row, and every retained buffer must have a
bounded owner and lifetime.
