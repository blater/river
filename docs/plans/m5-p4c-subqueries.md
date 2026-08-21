# M5 P4C robust subquery delivery plan

Status: active WIP rebased onto bounded-join alpha `4c50133`. The continuation
checkpoint is `19abbff` on `feature/p4c-subqueries`; no P4C slice is accepted
yet.

Owner: SQL semantics/execution lead. An independent relational-semantics and
allocation review is required before promotion.

## Recovery and restart checkpoint

- `wip/p4c-subqueries-snapshot` at `794641e` is the immutable pushed recovery
  point for the original P4C work.
- `feature/p4c-subqueries` at `19abbff` is the clean pushed continuation branch,
  rebased onto `4c50133`; `river-sql` and `river-engine` main sources compile.
- Before feature work resumes, migrate parser/lifecycle tests that still call
  the deleted singleton subquery API and fix the null zone-state failure in the
  joined P3 spill regression.
- These checkpoints preserve work and integration intent; they are not P4C
  promotion evidence. Focused semantics/allocation tests, affected full suites,
  design-debt checks, and independent review remain required.

## Outcome

P4C replaces River's singleton, raw, AND-only nested-query bridge with the same
bounded scalar and three-valued Boolean model used by ordinary queries. A
subquery is an explicit lazy Boolean/scalar edge in the canonical query graph,
not a synthetic comparison or an out-of-band executor.

The completed slice supports:

- multiple sibling and recursive `EXISTS`, scalar, and `IN` subqueries;
- placement under parentheses, `NOT`, `AND`, and `OR` with left-to-right lazy
  short-circuiting;
- computed local and correlated operands over any visible ancestor relation
  role, including n-table joined ancestors;
- child blocks using the admitted bounded n-table join source;
- fixed, exact, temporal, Boolean, raw/generated Unicode text, and typed NULL
  results;
- nested-filtered sources feeding direct projection, aggregation, grouping,
  `HAVING`, `DISTINCT`, ordering/spill, and P3 parent blocks; and
- truthful per-edge `EXPLAIN [ANALYZE]`, bounded caching, cleanup, and zero
  steady-state row allocation.

## Fixed graph and expression bounds

- At most 32 physical query blocks and 31 subquery edges per statement.
- At most eight subquery/predicate leaves per block.
- Existing per-block limits remain: 32 scalar nodes, 32 Boolean nodes, Boolean
  depth 16, and 256 literal membership values.
- At most eight relation roles per block and 32 physical relation roles across
  the whole query graph.
- A scalar or membership child projects exactly one bounded scalar expression.
- A reached membership child accepts at most 1,024 result rows; row 1,025
  returns `RESOURCE_EXHAUSTED`, even if an earlier candidate matched. `LIMIT`
  applies before this bound.
- Bounds are checked before execution. Query-graph shape exhaustion returns
  `QUERY_TOO_COMPLEX`; bounded value/arena exhaustion returns
  `RESOURCE_EXHAUSTED`.

## Canonical syntax and graph

`SqlQuery` owns one acyclic `SqlSubqueryGraph` with compact edge records:

- parent block and Boolean leaf;
- child block;
- edge kind: `EXISTS`, scalar result, or membership;
- negation/comparison metadata; and
- lexical depth.

The associated Boolean leaf is explicitly one of
`TEST_SUBQUERY_EXISTS`, `TEST_SUBQUERY_COMPARISON`, or
`TEST_SUBQUERY_MEMBERSHIP`. There is no `= 0` placeholder and no separate
scalar/existence/membership singleton array. Graph validation requires one
parent per non-root block, forward acyclic edges, reachability from the physical
root, exact edge/leaf agreement, and true parent-derived depth.

Admitted predicate forms are:

- `[NOT] EXISTS (query)`;
- any of the six comparisons where one scalar operand is `(query)`; and
- `scalar [NOT] IN (query)`.

Scalar subqueries inside arbitrary SELECT-list expressions, row-valued
subqueries, `ANY`/`ALL`, CTEs, recursive SQL, and lateral `FROM` items remain
`FEATURE_NOT_SUPPORTED`. This plan concerns predicate subqueries and their
one-value child projection.

Typed parameters are admitted in all blocks. Marker ordinals follow lexical SQL
order across parent and child text; binding is statement-global and preserves
typed NULL. Durable views containing subqueries remain fail-closed during P4C
and are admitted by the explicit next core-SQL slice. The n-table view v4
format already owns up to 32 ordered physical lineage entries; P4C does not
silently change catalog admission.

## Scope and binding

Every bound `COLUMN` scalar node stores `(queryBlock, relationRole, column)` and
its descriptor. Resolution follows lexical SQL scope:

1. search the current block's visible roles;
2. if an unqualified name is absent locally, walk ancestors nearest first;
3. a qualified name resolves the nearest visible matching alias/table role;
4. multiple matches at the selected scope are ambiguous and fail; and
5. siblings, descendants, and later roles are never visible.

Aliases shadow outer aliases. The binder must prove missing, ambiguous,
shadowed, sibling, future-role, and three-level ancestor cases explicitly.

Binding occurs in two phases. First resolve every block schema and child result
descriptor. Then bind all parent Boolean/scalar programs using those result
descriptors. A bare NULL on either side inherits the known descriptor; two
untyped sides return `DATATYPE_MISMATCH`. Non-NULL family compatibility and
Boolean ordering rules are unchanged.

There is one compact bound program per graph block, keyed by block rather than
depth so same-depth siblings cannot overwrite one another. It is an overlay on
the parser-owned canonical programs, not a second AST. The n-table plan's
role-aware row provider supplies local and ancestor values.

## SQL semantics

- `EXISTS` is two-valued and stops after the first accepted child row. Its
  SELECT expression is name/type checked but not evaluated or temporally
  prepared because SQL discards it.
- Scalar subquery: zero rows produces typed NULL; one row produces that value,
  including NULL; a second row returns `CARDINALITY_VIOLATION` (`21000`).
- `IN`: equality match dominates candidate NULL; no match plus a candidate NULL
  is `UNKNOWN`; otherwise it is false. `NOT IN` negates the final 3VL result.
- NULL left operand with an empty child is false for `IN` and true for
  `NOT IN`; with a nonempty child it is `UNKNOWN`.
- `FALSE` and `UNKNOWN` filter only at the enclosing `WHERE`/`HAVING` boundary.
- A Boolean branch skipped by short-circuiting does not execute its subquery and
  cannot raise row-time cardinality, conversion, or resource errors.

All admitted scalar comparison, arithmetic, cast, temporal, current-value, text
ownership, and overflow semantics come from the common expression engine.
P4C adds scope and lazy child results, not a parallel type system.

## Preparation and runtime ownership

Statement preparation is strictly separated from subquery execution:

1. parse and validate the complete graph;
2. resolve every physical table/role and bind every program;
3. prepare every predicate and scalar child projection, including zone/current
   validation, before any cursor opens; then
4. execute an edge only when its Boolean leaf is reached.

Thus an invalid zone in any executable predicate or scalar/membership child
projection wins before table access, including an empty or runtime-unreachable
branch, while DST gaps, overflow, and cardinality remain lazy row-time
outcomes. An `EXISTS` SELECT expression is not executable and is excluded as
specified above.

Use one query-owned `SqlSubqueryGraphExecution`, kept below the design-debt
limit by delegating to cohesive owners:

- a block-keyed evaluator/projection bank, eagerly warmed for every used block;
- a depth-keyed frame bank owning active cursors and full row images;
- a scalar/EXISTS/membership scanner implementing 3VL and row limits;
- a primitive uncorrelated result cache; and
- a per-edge plan counter bank.

Before any child cursor opens, copy every active ancestor composite row whose
borrowed storage could be invalidated. A frame owns all relation-role rows
needed when evaluation resumes after a descendant. Close active child cursors
inner-to-outer. Erase owned row/text/cache high-water only after close succeeds;
failed close retains ownership for retry.

One evaluator/workspace is required per active graph block. Callback-only state
saving is forbidden: recursion must not overwrite the parent Boolean program,
operands, zone plans, row provider, or truth continuation. Same-depth siblings
may share a runtime frame only after the prior child has closed and all result
state needed by the parent is owned elsewhere.

## Caching and allocation

Uncorrelated edges execute lazily on first reach and then cache per edge:

- `EXISTS`: one truth/state byte;
- scalar: one typed fixed slot or one lazy max-width owned text slot; and
- membership: values in one statement-owned primitive arena with per-edge
  linked segments, plus a lazy UTF-8 byte arena only for text membership.

Correlated scalar/membership edges execute for each parent row. Correlated
membership owns its left operand and scans/counts the complete child to enforce
the deterministic 1,024-row bound; it does not allocate a per-depth value
matrix. Nested uncorrelated cache fills must reserve independent segments so a
descendant cannot corrupt the parent's in-progress segment.

Optional cache payload is lazy. A session that never prepares a subquery must
not retain the 1,024-value arena. Text scalar caching must not allocate the
membership text arena. Ordinary fixed-width two-block retention and warmed
execution are measured separately from the explicit worst-case text arena.
There is no per-row allocation.

## Consumers, access, and plan truth

Graph filtering occurs once at the deepest physical source before any accepted
row is projected, accumulated, grouped, deduplicated, sorted, or appended to a
P3 store. Consumers use the graph execution's owned evaluated row until they
finish copying; they then release it. A predicate error publishes no aggregate
state, group, sort row, or outer/P3 row.

Child access selection may use one mandatory raw top-level-`AND` local-column to
ancestor-value edge for primary/index equality or a safely representable range.
Every candidate is residually rechecked. Computed/OR/NOT edges and unrepresentable
successors/extrema use a table scan. The selected plan must be stable for all
runtime values; otherwise report and use `TABLE`.

For every edge `EXPLAIN` reports kind, parent/child block and depth,
correlation, child filter leaves, and physical access. `ANALYZE` additionally
reports leaf invocations, child executions (cache misses), physical candidates,
accepted child rows, scalar/cardinality results, and parent rows accepted.
Cache hits increment invocations but not physical child rows. Plain `EXPLAIN`
never evaluates a child.

## Delivery slices

1. **C1 — graph and binding:** land the canonical graph, sibling/recursive
   parser, marker order, role-aware scope overlay, exact bounds, and eager
   preflight. Keep execution fail-closed until C2; delete singleton syntax APIs.
2. **C2 — common runtime:** land recursive evaluators, owned frames, scalar/
   `EXISTS`/membership semantics, lazy cache, terminal cleanup, and direct
   point/stream consumers. Delete `SqlNestedQueryExecution`,
   `SqlNestedPredicatePlan`, `SqlMembershipValues`, and every bridge evaluator.
3. **C3 — full consumers and n-table scopes:** admit joined children/ancestors,
   aggregation, group/`HAVING`, `DISTINCT`, order/spill, and P3 sources through
   the same graph runner. Add safe child access selection.
4. **C4 — plans and hardening:** complete per-edge plans/counters, temporal and
   corruption precedence, allocation/retention evidence, checkpoint/reopen of
   base data followed by ad-hoc queries, and full compatibility migration.

No slice may leave both old and new nested engines reachable or retained.

## Acceptance gate

Promotion requires:

- parser precedence with sibling `EXISTS`/scalar/`IN`, descendants, quotes,
  escaped quotes, true depth, exact 8/31/32 bounds plus one, copy/reset/reuse,
  and every exclusion returning its documented status;
- three lexical depths, alias shadowing, unqualified fallback, ambiguity,
  sibling/future rejection, and correlation to multiple joined roles;
- scalar zero/one/two-row results for all admitted families, typed NULL on
  either side, Unicode ownership/order, mixed decimal/temporal precision, and
  incompatible-family rejection;
- complete `IN`/`NOT IN` empty, NULL-left, NULL-candidate, match-plus-NULL, and
  1,024/1,025 behavior;
- parent and child continuation after recursion, two same-depth siblings, and
  nested uncorrelated cache replay;
- skipped `21000` under `TRUE OR`, eager invalid-zone failure before scans,
  statement-stable current values, fixed/IANA success, DST terminal repeat,
  close/retry, and reuse;
- direct, aggregate/group/`HAVING`, `DISTINCT`/order/spill, P3, and n-table
  ancestor/child consumers with no double filtering or partial publication;
- exact indexed/table equivalence including BIGINT and temporal extrema;
- exact plain/ANALYZE plan structure and cache-hit counters;
- warmed fixed, Unicode, correlated, recursive, and P3 execution within the
  existing allocation budget, with idle/scalar/membership retained deltas; and
- full SQL/engine suites, design-debt and diff policy, plus independent
  relational-semantics and performance/allocation review.

## Stop conditions

- One canonical graph and one common Boolean/scalar engine; no compatibility
  AST, singleton edge arrays, or parallel truth executor.
- No per-row allocation and no eager megabyte cache in ordinary sessions.
- First runtime error wins; cleanup remains retryable and no partial row or
  aggregate state is visible.
- No new owner above design-debt score 100 and no touched owner more than five
  points above its accepted baseline.
