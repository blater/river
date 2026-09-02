# M5 online schema evolution delivery plan

Status: approved architecture plan; implementation follows bounded n-table
JOINs and P4C.

Owner: catalog/schema lead with transaction and storage/recovery reviewers.
Any catalog, row-layout, or migration-job format change requires independent
durability/compatibility review.

## Outcome

River shall alter a populated database without requiring the application to
recreate tables or hold an exclusive schema lock for the duration of a scan or
backfill. Schema changes are transactional, crash-resumable, bounded, and
visible at one schema epoch. Existing transactions keep their admitted schema
snapshot; new transactions see the new schema only after atomic publication.

The completed priority slice includes:

- `ALTER TABLE` column, default, generated-value, nullability, constraint, and
  foreign-key changes;
- online `CREATE [UNIQUE] INDEX` and `DROP INDEX`;
- transactional view rename and `CREATE OR REPLACE VIEW` with dependency
  rebinding; and
- physical row rewrites and validation/backfill jobs where metadata-only
  publication is insufficient.

This is online single-node DDL, not concurrent multi-node schema consensus.

## Admitted SQL

The parser and catalog dispatcher shall admit these exact families:

```sql
ALTER TABLE t ADD COLUMN c type [column constraints]
ALTER TABLE t DROP COLUMN c
ALTER TABLE t RENAME COLUMN c TO d
ALTER TABLE t ALTER COLUMN c SET DEFAULT expression
ALTER TABLE t ALTER COLUMN c DROP DEFAULT
ALTER TABLE t ALTER COLUMN c SET NOT NULL
ALTER TABLE t ALTER COLUMN c DROP NOT NULL
ALTER TABLE t ALTER COLUMN c SET DATA TYPE type [USING expression]
ALTER TABLE t ADD CONSTRAINT name UNIQUE (c)
ALTER TABLE t ADD CONSTRAINT name CHECK (predicate)
ALTER TABLE t ADD CONSTRAINT name FOREIGN KEY (c) REFERENCES parent(id)
ALTER TABLE t DROP CONSTRAINT name
ALTER TABLE t ALTER COLUMN c SET GENERATED ALWAYS AS (expression) STORED
ALTER TABLE t ALTER COLUMN c DROP EXPRESSION
ALTER TABLE t ALTER COLUMN c ADD GENERATED ALWAYS AS IDENTITY
ALTER TABLE t ALTER COLUMN c DROP IDENTITY
CREATE [UNIQUE] INDEX name ON t (c)
DROP INDEX name
ALTER VIEW v RENAME TO w
CREATE OR REPLACE VIEW v AS query
```

The alpha-era eight-column, four-index, and one-column key/foreign-key limits in
this historical plan are superseded by
[`sql-shape-and-composite-key-capacity.md`](sql-shape-and-composite-key-capacity.md).
Online `ALTER` must consume the shared 1,024-column, 64-secondary-index,
32-key-part, composite-FK shape rather than reintroducing narrow shadow
formats. Virtual generated columns, expression indexes, deferrable constraints,
and `CASCADE` remain separate features and are recognized as
`FEATURE_NOT_SUPPORTED`, not silently approximated.

The primary-key column cannot be dropped or made nullable. A referenced object
cannot be dropped or incompatibly altered without first removing/replacing the
dependency. All DDL uses expected `StatusCode` results; exceptions are not
control flow.

## Schema identity and snapshots

The existing design conflates a logical table identity with its physical row
space and admits only the current `RelationalSchemaGate` version. Online row
rewrite requires a clean separation:

- **object ID:** stable catalog/dependency identity used by names, views, FKs,
  plans, and authorization;
- **storage generation ID:** physical heap/index generation containing rows for
  one schema layout; and
- **schema epoch:** immutable bound schema visible to a transaction/statement.

Replace the table catalog format directly before V1; do not add an adapter for
alpha records. A table record names its stable object ID, current storage
generation, schema epoch, column/constraint definitions, ready indexes, and an
optional migration job ID. Physical keys use the storage generation ID.

`RelationalSchemaGate` becomes a snapshot publisher rather than a build-long
exclusive gate. It may retain at most the current and immediately previous
schema epochs while transactions admitted under the previous epoch remain.
New admissions acquire the current immutable snapshot. Publication atomically
swaps the snapshot and increments the epoch; cached/bound statements carry the
epoch and rebind before execution when stale.

Only one schema job per database is active in this first slice. A second DDL
request returns `RETRY`. This bound keeps recovery, retained state, and conflict
ordering explicit; concurrency between independent DDL jobs is later work.

## Change classification

The planner classifies every request before catalog mutation.

**Metadata-only publication** uses one short serializable catalog transaction:

- rename table/column/index/view;
- set/drop a default for future writes;
- drop a default, generated rule, nonrestricting constraint, FK, or view
  dependency after dependency validation; and
- replace a view after binding the complete replacement and its lineage.

**Validation job** installs a pending rule for new writes, scans existing rows,
then publishes it:

- set `NOT NULL`;
- add CHECK, UNIQUE, or FK; and
- replace a view when dependency validation requires a stable multi-object
  snapshot but no row rewrite.

**Rewrite job** builds a new storage generation:

- add/drop a physical column;
- change a column type;
- add a stored generated column/expression;
- remove a stored generated expression when row encoding changes; and
- any default/nullability change that requires materialized existing values.

**Index job** builds or removes one secondary index while ordinary DML
continues. `DROP INDEX` is a short publication followed by deferred physical
cleanup; `CREATE INDEX` uses the resumable job protocol below.

The classifier is centralized. Parser or dispatcher code must not choose
physical migration behavior.

## Durable migration job

Add one catalog-owned `SchemaChangeRecord` with a directly versioned pre-V1
encoding. It contains bounded primitive state only:

- job ID, target object ID, source/target schema epochs and generations;
- operation kind and phase;
- source scan high-water key;
- durable change-log replay position;
- target index/generation IDs;
- bounded transform/validation program; and
- first terminal status plus cleanup state.

Phases are:

1. `RESERVED`: validate syntax, dependencies, capacity, and target schema;
2. `COPYING`: scan the source snapshot in bounded committed batches;
3. `CATCHING_UP`: replay post-snapshot mutations in commit order;
4. `VALIDATING`: enforce unique/FK/CHECK/not-null invariants;
5. `PUBLISHING`: take a short admission fence, drain the final log tail, and
   atomically publish catalog plus schema epoch;
6. `CLEANUP`: retire the old generation/index after old readers leave; and
7. `FAILED`: preserve exact failure and resumable cleanup ownership.

Checkpoint/reopen discovers the record and resumes the named phase. Malformed,
impossible, or cross-object state is `CORRUPTION`. A user cancel may move an
unpublished job to cleanup; once publication commits, cancellation cannot roll
the schema back.

## Concurrent DML and bounded change capture

At reservation, capture a repeatable source snapshot. Ordinary transactions
continue to read/write the published generation. Every committed mutation of
the target table after that snapshot appends a compact durable schema-change
record in the same transaction as the base mutation. The record owns the
primary key, mutation kind, and enough before/after values to transform the
target row or index entry deterministically.

The change log is bounded by an explicit byte/entry limit. Approaching the
limit wakes/calls the migration worker; if it cannot catch up, writes touching
the target return `RESOURCE_EXHAUSTED` rather than lose changes. Unrelated
tables continue. No in-memory-only delta determines correctness.

Copy uses `ensure`/version checks so a replayed newer mutation cannot be
overwritten by an older snapshot row. Catch-up is idempotent. Publication uses
a short schema admission fence: existing transactions finish on the old epoch,
new admissions pause, the final committed log tail is replayed, catalog state
is swapped, and new admissions resume on the new epoch.

## Operation semantics

### Online indexes

Refactor the existing `INDEX_BUILDING` batch path rather than adding another
builder. Building indexes are invisible to the planner. Snapshot copy and the
durable mutation log populate the index; every candidate is validated through
the normal typed key encoder. Unique build failure returns `UNIQUE_VIOLATION`
and cleans the unpublished index. Publication changes table and index records
atomically to `READY`; only then may plans select it. Drop first makes the index
unavailable atomically, waits for old schema snapshots, then removes storage in
bounded batches.

### Constraints and foreign keys

Every constraint receives a stable bounded constraint ID and name. Adding a
constraint first installs it as pending so all new writes are checked, then
validates the snapshot and change log, and finally publishes it as ready.
Dropping removes enforcement at one schema epoch after dependency checks.

FK validation protects referenced keys under the existing transaction/locking
rules and checks both tables at compatible schema snapshots. Alter/drop of the
referenced key/table conflicts while an FK remains. No orphan window is
permitted between validation and publication.

### Defaults and generated values

Defaults affect omitted values on future INSERT only. Setting/dropping a
default is metadata-only unless the same statement adds a non-null column whose
existing rows require materialization. A default is one source-free bounded
scalar program: literals/NULL, accepted current values, arithmetic, and casts;
it cannot reference a row, table, aggregate, or subquery.

`GENERATED ALWAYS AS (...) STORED` uses one deterministic bounded row-local
scalar program. It may reference columns in the same row but not current time,
session state, subqueries, aggregates, or other tables. INSERT/UPDATE always
recomputes it before CHECK/FK/index enforcement. Adding it rewrites/backfills;
dropping the expression preserves the stored values as an ordinary column.
Identity generation remains sequence-backed and has its own add/drop ownership
rules; it is never silently converted to a stored expression.

### Type and column changes

`SET DATA TYPE` requires binary identity or an explicit bounded `USING`
expression from the accepted scalar grammar. Every existing and concurrent
value must convert without truncation/overflow; otherwise the job fails with
the exact SQL status and the old schema remains published. Column drop checks
views, FKs, CHECK/generated programs, indexes, projections in durable objects,
and primary-key rules before reservation.

### Views

`CREATE OR REPLACE VIEW` fully parses/binds the replacement against one catalog
snapshot, captures exact n-table lineage, then atomically swaps definition and
dependency edges while preserving the view object/name. Existing statements
continue with their old bound plan until their schema epoch ends; new statements
bind the replacement. Rename changes the name/key while preserving object ID
and dependencies. A failed replacement leaves the old view untouched.

## Delivery slices

1. **S1 — immutable schema identity:** split object/storage IDs, publish
   versioned schema snapshots, add plan invalidation, replace catalog codecs,
   and migrate existing rename operations without changing their semantics.
2. **S2 — online indexes:** add the durable job/change log and convert the
   existing batched index builder/remover to snapshot/catch-up/publication.
3. **S3 — metadata and validation DDL:** SQL for defaults, nullability,
   named UNIQUE/CHECK/FK constraints, and replace/rename view; add pending-rule
   enforcement and resumable validation.
4. **S4 — row rewrites:** shadow storage generation, bounded transform/backfill,
   add/drop/type-change columns, and atomic generation publication.
5. **S5 — generated values:** stored deterministic programs, identity changes,
   dependency tracking, backfill, and mutation enforcement.
6. **S6 — hardening:** checkpoint/WAL crash matrix at every phase, backup/restore
   with active/completed jobs, bounded cleanup, allocation/retention, and full
   schema/dependency corruption evidence.

Each slice must expose only operations whose recovery and cleanup path is
complete. Unsupported later operations remain `FEATURE_NOT_SUPPORTED`.

## Acceptance gate

Promotion requires:

- exact grammar/status/dependency tests for every admitted operation and every
  excluded multi-column/virtual/cascade form;
- old/new transaction visibility across publication with no mixed schema in one
  transaction or statement, plus stale-plan rebind;
- concurrent insert/update/delete during index build, validation, and row
  rewrite; unique/FK/CHECK races; change-log exhaustion/backpressure; and no
  missed or duplicated target state;
- crash/reopen at every durable job phase and between final-log drain, catalog
  swap, schema publication, and cleanup; retry must be idempotent;
- add/drop/rename/type/default/not-null/generated operations for every admitted
  type, including Unicode, decimal scale, temporal precision/zone, NULL,
  conversion failure, and the current 8 KiB/1,024-column bounds;
- online unique/nonunique index equality/range equivalence before, during, and
  after publication; planner invisibility while building; bounded drop cleanup;
- FK changes involving both sides, referenced rename/drop/type changes, pending
  write enforcement, and rollback;
- view replacement over admitted n-table and derived/cardinality definitions,
  exact dependency swap, failed replacement preservation, reopen, and backup
  restore; subquery-bearing replacement remains fail-closed until the explicit
  durable-subquery-view slice immediately following this plan;
- generated recomputation order before constraints/indexes/WAL, backfill,
  explicit-value rejection, and expression corruption;
- exact job/system diagnostics and `EXPLAIN`/metadata schema visibility;
- no per-row migration allocation, bounded batch/change-log/storage growth,
  cleanup retry ownership, and measured foreground latency; and
- full relational/SQL/engine/backup suites, design-debt/diff checks, plus
  independent transaction, recovery, and durable-format reviews.

## Stop conditions

- No build-long global schema lock. Only reserve and publish use short admission
  fences; copy/validation/catch-up run in bounded batches.
- No correctness dependency on an in-memory queue or worker.
- One job record, one migration state machine, and one index builder path; no
  operation-specific recovery frameworks.
- Old storage is not deleted until no admitted schema snapshot can reference it.
- A failed unpublished job leaves the old schema authoritative and usable.
- No new owner above design-debt score 100 and no touched owner more than five
  points above its accepted baseline.
