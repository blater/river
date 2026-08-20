# M5 bounded n-table JOIN delivery plan

Status: approved architecture plan; implementation not started.

Owner: relational execution lead. Catalog-format changes require an independent
durability/compatibility review before promotion.

## Outcome

River shall execute and persist a bounded SQL join chain containing between two
and eight relation roles. The same joined-row source shall serve direct scans,
projection/cardinality pipelines, `EXPLAIN [ANALYZE]`, and durable views. No
second join executor or view-only execution path is permitted.

The completed slice supports:

- left-associative `INNER JOIN` and `LEFT JOIN` chains;
- a separate bounded `ON` Boolean program for every join stage and one
  post-join `WHERE` program;
- qualified expressions over any role already introduced plus the current
  right role;
- optional indexed access at every stage with complete residual rechecking;
- joined output feeding projection, aggregation, grouping/`HAVING`,
  `DISTINCT`, ordering, and bounded spill; and
- direct or deepest-derived durable views with exact ordered role lineage.

This plan deliberately does not include P4C subqueries. It establishes the
multi-role scope and row-provider boundary that P4C consumes next.

## Fixed bounds and syntax

- `MAXIMUM_JOIN_ROLES = 8`, including the first `FROM` relation.
- Roles are numbered in SQL occurrence order from zero through seven.
- Grammar is `FROM relation ( [INNER | LEFT] JOIN relation ON predicate )+`.
- A relation is a physical table or an already-expanded durable view. A joined
  chain may be the deepest source of the existing P3 block pipeline.
- Every physical query block retains the accepted P4 bounds: at most eight
  predicate leaves, 32 scalar nodes, 32 Boolean nodes, Boolean depth 16, and
  256 literal membership values. Those bounds apply independently to each
  `ON` program and to `WHERE`.
- A ninth role returns `RESOURCE_EXHAUSTED`. Recognized but excluded join forms
  return `FEATURE_NOT_SUPPORTED`.

Excluded from this slice are `RIGHT`, `FULL`, `CROSS`, `NATURAL`, and `USING`
joins; parenthesized/right-deep join trees; lateral relations; joined DML; and
subqueries inside a joined block. Equivalent inner-join orderings may be
written as a left-associative chain. P4C owns subqueries after this plan lands.

## Canonical command and binding model

Replace the singular `joinTableName`, `joinTableAlias`, `leftJoin`, and
`onPredicates` fields with one lazy command-owned `SqlJoinChain`:

- eight bounded relation-role records: table name, alias, and source kind;
- seven stage records: right role, join kind, and canonical `ON` program; and
- no copied or compatibility representation of the same predicates.

`SqlCommand`, `SqlQuery`, command copying, stored SQL reparsing, and
`BoundSqlQuery.Block` retain this chain as the sole syntax carrier. A compact
bound overlay stores resolved table definitions and resolved `(role, column)`
for every scalar `COLUMN` node. Unqualified names must resolve uniquely across
the roles visible at that program point; qualified names resolve exactly one
table name or alias. Duplicate aliases and table-name/alias collisions are
`INVALID_EXTERNAL_INPUT`.

Self-joins are admitted only with distinct explicit aliases. Their separate
roles may resolve to the same physical table ID.

Binding is left to right. Stage `i` may reference roles `0..i+1`; references to
a later role fail before execution. `WHERE` and projection may reference all
roles. Type comparison, NULL inference, temporal preparation, and owned text
use the existing common scalar/Boolean machinery with a role-aware row
provider; they must not add role-specific type logic.

## Execution architecture

Replace the dual-row `SqlJoinRowSource`/`SqlJoinCursors` shape with one concrete
query-owned `SqlJoinChainSource`. Both direct execution and the P3 deepest
source call this owner. It contains:

- a fixed stage-state array for seven joins;
- one reusable relational cursor/result carrier per active role;
- one lazy owned row image per role whose borrowed row must survive opening or
  advancing another cursor;
- stage match/null-extension state; and
- one role-aware `SqlRowProvider` consumed by predicates and projections.

No interface is introduced unless a second non-SQL provider actually appears.
The direct and P3 consumers are adapters over the same concrete source.

For each left composite row, stage execution is:

1. select/open the current right access candidate;
2. evaluate the complete `ON` program left to right with SQL 3VL;
3. mark the stage matched only when `ON` is `TRUE`;
4. emit every true pair, or exactly one right-null-extended composite for a
   `LEFT JOIN` stage with no true pair; and
5. continue that composite into the next stage.

The post-join `WHERE` runs only after all stages, including every required null
extension. `FALSE` and `UNKNOWN` filter. A later join may observe NULLs created
by an earlier `LEFT JOIN`. Projection occurs after `WHERE` and publishes
atomically: an error in any lane leaves the caller result unavailable.

Raw and generated `VARCHAR` values are copied into the existing bounded owned
operand/result storage before their source row can advance. Comparison is by
Unicode scalar value. Temporal/current/zone programs for all `ON`, `WHERE`,
and projection programs are prepared before any table cursor opens.

## Access and plan truth

For each stage, select at most one mandatory top-level-`AND` raw equality from
an earlier role to the current right role. It may choose primary, unique-index,
or nonunique-index lookup. Other predicates, equality under `OR`/`NOT`,
computed operands, and unsupported normalized bounds use a full right scan.

An access edge is only a candidate generator. The complete `ON` program always
rechecks it. Descriptor/precision normalization follows the existing exact,
decimal, text, and temporal domains; if a physical bound cannot be represented
without changing results, use a table scan.

`EXPLAIN` emits, in stage order:

- one source row for role zero;
- one `JOIN` or `LEFT JOIN` row per later role with its `ON` leaf count;
- the actual right physical access (`LOOKUP`, `INDEX`, or `TABLE`);
- the post-join `FILTER`, if present; and
- existing parent projection/cardinality/sort stages.

`ANALYZE` records candidates visited, `ON`-accepted pairs, null-extended rows,
post-`WHERE` accepted rows, and published stage rows. Plain `EXPLAIN` binds and
prepares no runtime expression and opens no cursor.

## Durable view format

Replace catalog view v3 atomically with pre-V1 v4. There is no compatibility
decoder. The immediately scheduled durable-subquery-view slice also consumes
v4, so the format is bounded once for the complete physical query graph rather
than replaced again. A v4 record owns:

- magic/version and bounded UTF-8 name/query lengths;
- lineage count `1..32`; and
- 32 ordered physical table IDs, with unused slots canonically zero.

For an n-table definition, IDs are stored in SQL role order. The later P4C
consumer extends the same canonical order to depth-first query-block order and
then role order within each block. IDs are never sorted. Duplicate IDs are
valid only when the rebound SQL contains distinct explicitly aliased roles
resolving to that same table. Decode rejects invalid counts/IDs, nonzero unused
slots, malformed UTF-8, length mismatches, and noncanonical records as
`CORRUPTION`.

`CREATE VIEW` parses and binds the full definition, captures all role IDs, then
writes the catalog record in the same transaction. Reopen reparses and rebinds
the SQL and requires exact lineage-count and ordered-ID equality before
execution.
Dependency checks treat the ordered role list as a set: rename/drop/schema
change of any referenced table conflicts until the view is replaced or
dropped. Backup copies v4 bytes unchanged and restore revalidates them.

## Delivery slices

1. **J1 — syntax and scope:** land `SqlJoinChain`, left-to-right parsing,
   copying/reset, role-aware binding, exact bounds/statuses, and no execution
   admission beyond two roles until J2 is present.
2. **J2 — common execution:** replace the dual-row source with
   `SqlJoinChainSource`; prove multi-stage inner/left semantics, access residual,
   projection ownership, terminal cleanup, and no per-pair allocation.
3. **J3 — consumers and plans:** feed the existing P3 pipeline and direct sort
   path; add truthful stage plans/counters and spill evidence. Remove all
   singular join compatibility fields and executors.
4. **J4 — durability:** replace view v3 with v4, admit direct/deepest-derived
   n-role views, enforce every dependency, and prove checkpoint/WAL reopen,
   backup/restore, corruption, and allocation boundaries.

Each slice is committed only when its newly admitted syntax has a real runtime
consumer or remains explicitly fail-closed. No partial silent admission.

## Acceptance gate

Promotion requires focused and full affected-module evidence for:

- three- and eight-role inner chains; ninth-role exhaustion and reuse;
- mixed `INNER`/`LEFT` stages, including earlier null extension consumed by a
  later `ON`, and `ON`-true/late-`WHERE`-false behavior;
- role-qualified fixed, decimal, temporal, Boolean, raw/generated Unicode text,
  typed NULL, and incompatible-family outcomes;
- primary/unique/nonunique lookup and no-edge scans at multiple stages, with
  identical indexed/table results;
- direct projection/order and P3 aggregate/group/`HAVING`/`DISTINCT`/spill;
- exact `EXPLAIN ANALYZE` stage order and counts;
- runtime overflow/DST/text corruption after an earlier candidate, terminal
  repeat, close/retry, unavailable partial results, and session reuse;
- durable distinct-table and self-join views, every dependency operation,
  swapped/missing/corrupt lineage, checkpoint/WAL reopen, and backup restore;
- warmed primitive and Unicode chains within the existing SQL row-allocation
  budget, with retained memory measured and bounded; and
- full SQL/engine/backup suites, design-debt policy, diff checks, and an
  independent relational-semantics plus durable-format review.

## Stop conditions

- No per-row or per-pair allocation.
- No second executor, predicate carrier, row store, or view compiler.
- No touched owner may exceed its accepted design-debt baseline by more than
  five points, and no new owner may score above 100.
- Cleanup failure retains enough ownership for an exact retry; runtime status
  outranks cleanup status, and no partial row is visible.
- J4 is not accepted until all role dependencies are durable and enforced.
