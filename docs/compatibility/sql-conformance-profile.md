# River SQL conformance profile

<!-- markdownlint-disable MD013 -->

Status: active M5 contract; U02a-U02e accepted on 2026-08-14; U02f
ordered-scalar index format and direct-root expression checkpoint accepted on
2026-08-15; deterministic column expression `CHECK` checkpoint accepted on
2026-08-15; compile-time view/derived projection composition and block-scoped
cardinality-stage checkpoints accepted on 2026-08-15; bounded JDBC temporal-result mapping and remote-value
validation checkpoint accepted on 2026-08-15; bounded engine/protocol typed-
parameter checkpoint accepted on 2026-08-15; bounded generalized-predicate
P4A and direct/staged P4B checkpoints accepted on 2026-08-20

This profile is the semantic authority for River's admitted pre-V1 SQL
surface. Parser acceptance is not support. A feature is supported only when
its SQL, catalog, durable row/index/WAL, embedded API, protocol, JDBC, recovery,
and error behavior satisfy the applicable fixtures.

## Current and target type surface

The current implementation supports `BIGINT`, `BOOLEAN`, `DECIMAL(p,s)`,
`VARCHAR(n)`, `DATE`, `TIME(p)`, local `TIMESTAMP(p)`, and
`TIMESTAMP(p) WITH TIME ZONE`, including session zones and statement-stable
current values. U02f has accepted raw temporal values through projection,
joins, grouping, `HAVING`, distinct/order, sort spill, view/derived/nested
queries, checkpoint-base/WAL replay, backup/restore, and warmed allocation
paths. The accepted P4A contract uses one bounded Boolean predicate program at
the direct root and at every cardinality-changing derived-table or durable-view
stage. Each program admits at most eight leaves, 32 shared scalar postfix
nodes, 32 Boolean control nodes, depth 16, and 256 shared membership values.
It implements `NOT`, parentheses, SQL `AND`-before-`OR` three-valued logic,
bare Boolean truth, `IS [NOT] TRUE`/`FALSE`/`UNKNOWN`/`NULL`, and all six
comparisons with scalar expressions on both sides. Inclusive `BETWEEN` bounds
and `IN`/`NOT IN` members remain typed literal or parameter values. Generated
`VARCHAR` ranges and membership compare owned text by Unicode scalar value.
Computed scalar-aggregate operands and filtering, plus filtering before raw
`GROUP BY`/grouped aggregates/`DISTINCT`, use that same program. A raw
direct-root grouping key may
also feed one column-bearing primitive computed aggregate operand. Direct-root
scalar and grouped aggregates may apply a bounded post-aggregate `HAVING`
clause. Up to eight structurally deduplicated aggregate invocations feed at
most eight predicate leaves sharing 32 postfix nodes and 256 membership values;
grouped execution reserves its first physical lane for the key and therefore
admits seven operand-bearing invocations plus lane-free `COUNT(*)`. Predicates
use SQL `AND`-before-`OR` three-valued logic and admit the six comparisons,
`IS [NOT] NULL`, inclusive `BETWEEN`, and `IN`/`NOT IN`. Unique selected
aliases, the raw group key or its alias, and exact repeated or hidden aggregate
invocations are valid leaves. Direct raw `VARCHAR` group/aggregate leaves and
generated fixed-width-to-text results compare by owned UTF-8 content; a raw
text leaf embedded inside another postfix operation remains fail-closed. A selected
direct-root, column-bearing exact-numeric or temporal
fixed-width expression may also provide one `ORDER BY`, `DISTINCT`, or grouping
key; computed Boolean and text keys remain deferred. Computed ordering names a
unique selected alias, while computed grouping repeats the selected expression
exactly; these keys are materialized and do not provide expression-index
access. Compile-time projection composition now inlines bounded postfix
programs through selected aliases in durable views and ad-hoc derived-table
chains, including `NULL`, current values, temporal text casts, and `AT TIME
ZONE`. The flattened command executes through the accepted direct-root row
path, and a selected composed output may use its materialized `ORDER BY` path.
Every block in a composed chain may apply its own bounded predicate before that
block's projection, grouping, aggregate, `HAVING`, or distinct phase. A
predicate may reference a selected composed output through the existing
compile-time expression composition. A chain may contain
`DISTINCT`, scalar aggregate, or grouped aggregate/`HAVING` cardinality stages
in ad-hoc derived tables and durable views. Each aggregate stage retains the
accepted direct-root aggregate-set and three-valued `HAVING` semantics, and
all fixed-width and UTF-8 results are copied into an owned stage row before
the child advances. Execution accumulates each stage once from the deepest
source outward and publishes no outer row until every intermediate stage has
succeeded. Two lazy spill-backed stores alternate between stages; each store
admits at most 65,536 rows and 256 MiB of encoded rows, sort keys, and retained
index arenas, returning `RESOURCE_EXHAUSTED` at either bound. `EXPLAIN
[ANALYZE]` reports each cardinality stage, logical operation, physical sort,
and analyzed stage row count. `ORDER BY` is admitted only at the outer stage
and must resolve to one selected output; inner-stage ordering is explicitly
deferred rather than silently discarded.

Durable view records use strict UTF-8 catalog format v4. The fixed header owns
an ordered lineage count and 32 physical-table ID slots; used IDs are in SQL
role order and every unused slot is zero. Repeated physical IDs are valid only
for distinctly aliased self-join roles. Older versions, malformed or
noncanonical counts/IDs, nonzero unused slots, truncated or trailing records,
lineage mismatches, malformed UTF-8, and unpaired UTF-16 input are rejected
rather than adapted during this pre-V1 format replacement.
Projection-only stored
views retain their flattened point/index fast path; only a real cardinality
barrier selects staged execution. A joined block contains two through eight
left-associative `INNER`/`LEFT` relation roles, with one bounded canonical `ON`
program per stage and one post-chain `WHERE` program. Each `ON` sees the roles
already introduced plus its current right role; `WHERE` and projection see all
roles. `ON TRUE` alone establishes stage match state, and each `LEFT` stage
publishes one current-right NULL extension only after no candidate made its
`ON` true. A mandatory raw equality may drive primary/secondary access or the
bounded hash strategy, but every candidate receives the complete residual.
Other admitted shapes use the common nested-loop fallback. Hashing covers the
fixed, exact decimal, temporal, Boolean, and Unicode-scalar equality families;
after the existing 1,024-row/4 MiB store spills, the alpha reports and uses a
stable bounded nested fallback. Partitioned spill hash, merge join, and cost
planning remain performance work. Selected scalar expressions may reference
any role, and generated/raw `VARCHAR` results are published from owned text.
Direct joined `ORDER BY` accepts a selected output name; qualified physical
role expressions remain `FEATURE_NOT_SUPPORTED`, and ambiguous unqualified
names remain invalid. One joined chain may instead be the deepest source of an
ad-hoc P3 pipeline: its owned projected rows feed the existing
alternating stores, and parent projection, scalar or grouped
aggregate/`HAVING`, `DISTINCT`, and outer `ORDER BY` stages retain the same
spill, atomic-publication, and `EXPLAIN [ANALYZE]` contracts. A JOIN in a
nondeepest block and multiple joined blocks fail closed. Direct and
deepest-derived joined definitions may be durable views. CREATE binds every
role before catalog mutation; expansion reparses the stored query and requires
an exact ordered lineage match before execution. Every referenced table blocks
DROP and schema rename while the view exists, and the lineage survives
checkpoint/WAL replay and offline backup/restore. Distinctly aliased durable
self-joins are admitted.
Nested/correlated column-to-column edges remain equality-only; auxiliary raw
ranges, membership, NULL, and truth tests retain their prior admission, while
computed or generalized correlated/subquery predicates remain deferred. More
than one `AT TIME ZONE` operation in one scalar operand and aggregate stages
inside a join, nested, or correlated context also remain U02f work. A column may declare one
durable `CHECK` whose bounded expression references only that column. All
column checks share a 32-node/table arena; exhausting it returns
`RESOURCE_EXHAUSTED`. An admitted expression uses
context-free fixed-width temporal operations: `EXTRACT`, date arithmetic,
same-family temporal precision casts, or `DATE`/local-`TIMESTAMP` casts. The
comparison RHS is one typed literal. NULL/unknown passes, false is `23514`,
and expression overflow retains its exact status. Current values, `AT TIME
ZONE`, session-dependent local/zoned casts, text, cross-column and table-level
check expressions, membership/ranges, and subqueries remain deferred. Direct
root mutations admit one shared 32-node fixed-width assignment arena:
source-free `INSERT` values and primary keys and old-row `UPDATE` assignments
with simultaneous assignment semantics. `UPDATE` and `DELETE` filtering uses
the common bounded Boolean predicate program. Exact numeric and temporal
operations, typed parameters and NULLs,
statement-stable current values, and explicit/session zone conversions use the
same bound evaluator as row projections. `INSERT` column references, generated
text assignment results, joins, nested/derived mutation sources, and computed
DML outside direct root remain `FEATURE_NOT_SUPPORTED`.
NULL assignments still obey target nullability, and identity reservation keeps
the existing gap-on-failure sequence policy while statement failure rolls back
row, index, WAL, check, unique, and foreign-key effects. The final
cross-boundary fault gate also remains before this M5 profile is accepted. The
ordered-scalar format now carries a primitive namespace plus the
complete signed 64-bit value, so `BIGINT`, `DECIMAL`, `DATE`, `TIME`, and both
timestamp families support their full admitted domains without lossy packing.

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
- No implicit assignment conversion loses a fractional part. The binder
  derives a stable result precision and scale for each admitted expression;
  division, precision-bound result derivation, and an explicit scale-reducing
  cast use round-half-even.
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
  area/location region ID such as `Europe/London` from the supported JDK
  tzdb. Legacy abbreviations and link aliases without an area component are
  not admitted.
- `DATE`, `TIME`, and `TIMESTAMP` without time zone are local values. They do
  not inherit or carry the session zone.
- `TIMESTAMP WITH TIME ZONE` is an instant. Equality, ordering, uniqueness, and
  indexes compare the instant, regardless of the offset used at input.
- Converting a local timestamp to an instant uses an explicit zone when one is
  supplied, otherwise the session zone. A nonexistent DST-gap local time is an
  error. An ambiguous local time in an IANA region is an error. A conversion
  using an explicit fixed offset is unambiguous and selects that instant; River
  does not invent a combined region-plus-offset SQL syntax.
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

`EXTRACT(SECOND FROM value)` returns exact `DECIMAL(2+p,p)`, where `p` is the
declared fractional-second precision of the source; the `p=0` result is
`DECIMAL(2,0)`. Other admitted `EXTRACT` fields return `BIGINT`. Because a
zoned timestamp retains only its instant, its ordinary field extraction uses
UTC chronology and `TIMEZONE_HOUR`/`TIMEZONE_MINUTE` both return zero. To
extract wall-clock fields for a region or fixed offset, first convert the
instant explicitly with `AT TIME ZONE`.

Temporal precision widening preserves the raw value. Explicit narrowing is
lossless-only: a value not aligned to the target precision fails with `22008`.
`DATE` casts to local `TIMESTAMP` at midnight; local `TIMESTAMP` casts to
`DATE` only at exact midnight. Local `TIMESTAMP` and zoned `TIMESTAMP` convert
through the session zone and retain their precision, with the gap/overlap
rules above. Direct `DATE`/zoned-timestamp and `TIME` cross-family casts are
not admitted. Canonical zoned-timestamp text is UTC with a `+00:00` suffix;
the input offset is not recoverable and presentation never changes implicitly
with session state.

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
The current bounded U03a result checkpoint implements the Java-time and
compatibility accessors, temporal precision metadata, canonical strings, and
descriptor/domain validation for remote fixed-width values. Until query
nullability is derived from the bound projection, query-open metadata carries
it in the versioned response envelope, and JDBC reports exact nullable or
non-null columns. The bounded
typed-parameter checkpoint carries descriptor-tagged fixed values, strict UTF-8
text, and typed or context-inferred `NULL` through the embedded API and protocol
v3 without rendering SQL. Positional markers are admitted in direct outer data
statements, including an outer statement over a stored projection-only view;
stored definitions and explicit derived, nested, correlated, and subquery
topologies containing markers remain `FEATURE_NOT_SUPPORTED`. Typed `NULL` is
supported in direct mutation values, direct fixed-width mutation expressions,
and membership lists; scalar comparison, range, and projection uses remain
fail-closed until their explicit three-valued literal carrier exists. JDBC
prepared bindings and bounded batch
snapshots use the typed carrier without SQL rendering. Authenticated all-type
JDBC and CLI fixtures exercise all eight admitted families through TLS and
token authentication. The combined checkpoint/reopen, backup/restore, and
injected-fault fixture remains U06-owned.

## Required SQLSTATEs

| Condition | SQLSTATE |
| --- | --- |
| Parameter count mismatch | `07001` |
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
