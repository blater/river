# M5 P4C robust subquery delivery plan

Status: Alpha 2 implementation contract approved. P4C-0 through P4C-6 are
accepted on `feature/p4c-subqueries` at `f85c499`. Alpha 2 remains incomplete;
the next production task is P4C-7.

Owner: SQL semantics/execution lead. An independent relational-semantics and
allocation review is required before promotion.

## Recovery and restart checkpoint

- `wip/p4c-subqueries-snapshot` at `794641e` is the immutable pushed recovery
  point for the original P4C work.
- `feature/p4c-subqueries` at `f85c499` contains accepted P4C-0 through P4C-6:
  canonical graph tests, lexical marker ordering, `(block, role, column)` scope
  binding, computed child projection, contract-level semantics fixtures, and
  joined root/child graph execution through the common n-table join source.
- P4C-4 covers 2/3/8-role joined blocks, LEFT null extension, later-role and
  Unicode ownership, terminal failure/reuse, P3 atomicity, and warmed
  allocation. Joined child aggregate/group/distinct/order/P3 shapes remain
  fail-closed by design.
- P4C-5 completes scalar zero/one/multiple-row and LIMIT semantics, discarded
  `EXISTS` projections, full membership 3VL, per-edge 1,024/1,025 enforcement,
  bounded uncorrelated replay/fallback, all-family values, Unicode erasure,
  temporal failure/reuse, and warmed allocation.
- P4C-6 adds prepare-stable safe primary/index child access with residual
  rechecks and conservative TABLE fallback, plus a deterministic six-row
  per-edge `EXPLAIN [ANALYZE]` shape and truthful cache/scan/result/parent
  counters.
- The next implementation gate is P4C-7 below. The affected
  focused suites and design-debt checks are green. The former grouped-HAVING
  null-zone and `temporal_derived` parser/reopen regressions were repaired in
  `1a51d8a`. Full-engine promotion still has one baseline non-JOIN point
  subquery status-oracle mismatch, owned by P4C-7 consumer integration.
- These checkpoints preserve work and integration intent; they are not P4C
  promotion evidence. Focused semantics/allocation tests, affected full suites,
  design-debt checks, and independent review remain required.

## Alpha 2 scope decisions

The following decisions are fixed for implementation and are not left to an
individual builder:

- A subquery block is a physical `SELECT`/`SCAN` over one table or one admitted
  two-to-eight-role n-table join. It may have the canonical `WHERE`, one
  computed scalar projection, and `LIMIT`.
- Aggregate, `GROUP BY`, `HAVING`, `DISTINCT`, `ORDER BY`, and a P3 derived
  pipeline inside a child subquery remain `FEATURE_NOT_SUPPORTED`. Those
  operations remain admitted for consumers outside the nested-filtered source.
- A scalar subquery may be either operand of one of the six comparisons.
  `EXISTS` and subquery membership retain the grammar below.
- Every column node in a child predicate or projected scalar expression uses
  the same lexical resolver. It may reference local roles or any visible
  ancestor role. An unqualified name resolves locally first, then at the
  nearest ancestor scope containing exactly one match; qualification is
  required to disambiguate multiple visible roles.
- Typed parameter markers are admitted in parent and child blocks. Their
  ordinals follow the original SQL text left to right across the complete
  graph; the parser must not derive marker order from depth-first execution.
- Joined ancestors and joined children are part of Alpha 2. Durable views over
  any subquery graph remain fail-closed for the separately prioritized durable
  subquery-view slice.
- An `EXISTS` SELECT expression is resolved for names and basic shape only. It
  is not type-prepared, temporally prepared, evaluated, or allowed to fail at
  runtime because SQL discards that value.

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
- A scalar or membership child projects exactly one bounded scalar expression;
  its column nodes use the lexical local/ancestor scope rules above.
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
- any of the six comparisons where exactly one scalar operand is `(query)`; and
- `scalar [NOT] IN (query)`.

Scalar subqueries inside arbitrary SELECT-list expressions, row-valued
subqueries, `ANY`/`ALL`, CTEs, recursive SQL, and lateral `FROM` items remain
`FEATURE_NOT_SUPPORTED`. This plan concerns predicate subqueries and their
one-value child projection.

Typed parameters are admitted in all blocks under the Alpha 2 ordering rule
above; binding is statement-global and preserves typed NULL. Durable views
containing subqueries remain fail-closed during P4C and are admitted by the
explicit later core-SQL slice. The n-table view v4 format already owns up to 32
ordered physical lineage entries; P4C does not silently change catalog
admission.

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

## Prioritized implementation task list

The rebased continuation already owns the canonical graph, graph executor,
frame bank, primitive cache, value scanner, access selector, and plan carrier,
and deletes the legacy singleton nested engine. Work proceeds through the
following dependency graph; a higher-numbered task does not displace an open
lower-numbered blocker.

```text
P4C-0 focused-test baseline
  |
  +--> P4C-1 parser/marker order --------+
  +--> P4C-2 lexical scope/projection ---+--> P4C-4 n-table graph runtime --+
  +--> P4C-3 acceptance fixtures --------+--> P4C-5 value/cache semantics --+--> P4C-7 consumers
                                           P4C-6 plan/access carrier -------+       |
                                                                                   v
                                                                        P4C-8 hardening
                                                                                   |
                                                                                   v
                                                                        P4C-9 promotion
```

### P4C-0 — restore a trustworthy focused-test baseline

Status: accepted at `019fe86`.

This is the only task that starts before feature implementation.

- Migrate `SqlParserTest` and `SqlCommandLifecycleTest` from the deleted
  scalar/existence/membership singleton methods to `SqlSubqueryGraph` edges.
- Compile all SQL and engine tests, then migrate the existing
  `SqlNestedQueryTest` and `SqlExecutionOwnershipTest` expectations that encode
  the old bridge. Do not restore production compatibility methods, disable
  tests, or turn an implemented semantic regression into an unsupported case.
- Keep tests for already-supported behavior green. Add new Alpha 2 behavior as
  an acceptance fixture alongside the production task that admits it.
- Commit this test migration separately. Its gate is green SQL/engine test
  compilation plus the four focused classes above. Production changes are
  limited to a demonstrably necessary test seam or a proven regression in the
  behavior already admitted at the starting checkpoint; P4C-0 does not admit
  new syntax or semantics.

### First parallel wave after P4C-0

Status: accepted and integrated through `4a77011` (`ef64e4f` P4C-1,
`8cc3676` P4C-2, and `26dc6c5` P4C-3). Independent relational-semantics review
is GO; the joined-graph temporary rejection is pinned by a real session test.

P4C-1 and P4C-2 may run in parallel in separate worktrees. P4C-3 may also run
in parallel because it owns only new test fixtures and performs read-only
contract review. One lead integrator owns the bound overlay contract and lands
the streams in task-number order.

| Task | Deliverable | Primary ownership | Completion gate |
| --- | --- | --- | --- |
| **P4C-1** | Parse scalar subqueries on either comparison side and assign typed-marker ordinals in original SQL lexical order across every graph block. Preserve exact graph bounds, copy/reset, and FNS classifications. | `river-sql`: `SqlNestedQueryParser`, `SqlNestedSubquerySource`, `SqlQueryParser`, `SqlSubqueryGraph`, `SqlSubqueryLeafRegistry`, parser tests | Sibling/descendant/quoted-source parsing, marker-order/type/null tests, 8/31/32 bounds plus one, parser allocation/reuse gate |
| **P4C-2** | Generalize child predicate and projected scalar binding to `(block, role, column)` lexical scope. Resolve local roles first, then the nearest unique ancestor; support joined blocks and owned text without a second AST. | `river-engine`: `SqlQueryBlockBinder`, `SqlNestedProjectionBinder`, `SqlNestedProjectionExecution`, `SqlNestedRowProvider`, Boolean scalar binders | Three scopes, shadowing, local fallback, ambiguity/future/sibling rejection, joined-role projection, all-family typed NULL/text tests |
| **P4C-3** | Build contract-level fixtures and an independent semantics oracle for sibling/recursive 3VL, correlation, n-table roles, consumer atomicity, and exact failure precedence. It must not edit production owners. | New focused test classes split by parser, scope/value semantics, consumers, and lifecycle | Tests compile against the canonical APIs; each future assertion is enabled only with its owning production task; no duplicated helper engine |

P4C-1 and P4C-2 meet at one explicit handoff: the parser supplies stable graph
block/edge IDs and lexical marker ordinals; the engine binds those IDs to the
block/role/column overlay without reparsing SQL text.

### Second parallel wave after P4C-1 and P4C-2

P4C-4, P4C-5, and the structural portion of P4C-6 may proceed concurrently
after the overlay handoff is frozen. Their production file ownership is
disjoint. Runtime counter calls from P4C-4/P4C-5 into P4C-6 are agreed before
editing; no stream creates another graph executor, row provider, cache, or
plan framework.

| Task | Deliverable | Primary ownership | Completion gate |
| --- | --- | --- | --- |
| **P4C-4 (accepted)** | Replace the temporary root/child JOIN rejection with the existing `SqlJoinChainSource` and role rows. Own every active ancestor composite before a child cursor opens; preserve INNER/LEFT semantics, stage-local ON, final WHERE, and retry-safe inner-to-outer close. | `SqlSubqueryFrames`, `SqlSubqueryCandidateEvaluator`, `SqlSubqueryGraphExecution`, n-table source adapters | Joined parent and joined child at 2/3/8 roles, later-role correlation, LEFT-null continuation, Unicode lifetime, terminal failure/reuse, no subquery-only join path |
| **P4C-5 (accepted)** | Complete scalar/`EXISTS`/membership evaluation, child projection, lazy cache, LIMIT/cardinality, recursive continuation, and resource bounds using the common expression engine. | `SqlSubqueryLeafEvaluator`, `SqlSubqueryValueScanner`, `SqlSubqueryResultCache`, projection execution | Scalar 0/1/2, complete IN/NOT IN 3VL, sibling and descendant replay, 1,024/1,025, long/short text erase, eager-zone versus lazy-runtime precedence |
| **P4C-6 (accepted)** | Stabilize child access and per-edge plan/counter carriers. Select only safe mandatory raw edges; otherwise use and report TABLE. Plain EXPLAIN binds but never executes. | `SqlSubqueryAccess`, `SqlSubqueryPlan`, plan description tests | Equality/range/extrema equivalence, computed fallback, identical EXPLAIN/ANALYZE shape, truthful invocation/cache/candidate/accepted/result counts |

### Integration and promotion path

These tasks are intentionally serialized through the lead integrator because
they touch shared query lifecycle and consumer routing.

1. **P4C-7 — consumer integration.** Apply graph filtering exactly once before
   direct point/stream projection, scalar and grouped aggregation, `HAVING`,
   `DISTINCT`, sort/spill, and deepest P3 publication. Consumers use the owned
   evaluated row until all values are copied, then release it. A nested error
   publishes no aggregate, group, sort/store row, or point result.
2. **P4C-8 — lifecycle and allocation hardening.** Prove eager all-program
   preflight before any scan; lazy row-time error/short-circuit behavior;
   terminal status repetition; close-failure retry; text/cache/frame erasure;
   checkpoint/reopen of base data; zero per-row allocation; and bounded idle,
   scalar, membership, recursive, joined, spill, and P3 retained deltas.
3. **P4C-9 — Alpha 2 promotion.** Run the full SQL and engine suites, focused
   temporal/ownership/allocation/plan gates, `tools/designdebt.sh`, diff and
   clean-checkout policy, then obtain independent relational-semantics and
   performance/allocation verdicts on the exact commit. Update conformance,
   status, roadmap, and limitations only after those gates are green.

The branch may be committed at the numbered internal waypoints, but Alpha 2 is
promotable only as the complete C1-C4 vertical slice. Durable subquery views,
child cardinality stages, TPC-C storage work, and unrelated JDBC work do not
enter these workstreams.

### Parallel-work operating rules

- Parallel builders use separate Git worktrees, disjoint file ownership, and
  separate `GRADLE_USER_HOME` plus `--project-cache-dir`. Only one Gradle build
  runs in any shared checkout.
- The lead integrator alone changes shared lifecycle owners such as
  `SqlQueryExecution` and performs final consumer composition.
- Test-fixture work does not modify production contracts. A read-only
  semantics/allocation reviewer may run alongside every wave and reports
  concrete blockers to the integrator.
- Each waypoint lands as a small reviewed commit with focused success and the
  material failure/reuse boundary. Parallel branches are rebased and integrated
  one at a time; broad merge-conflict resolution is not an architecture step.

## Delivery slices

1. **C1 — graph and binding:** land the canonical graph, sibling/recursive
   parser, marker order, role-aware scope overlay, exact bounds, and eager
   preflight. Keep execution fail-closed until C2; delete singleton syntax APIs.
2. **C2 — common runtime:** land recursive evaluators, owned frames, scalar/
   `EXISTS`/membership semantics, lazy cache, terminal cleanup, and direct
   point/stream consumers. Delete `SqlNestedQueryExecution`,
   `SqlNestedPredicatePlan`, `SqlMembershipValues`, and every bridge evaluator.
3. **C3 — full consumers and n-table scopes:** admit joined children/ancestors,
   and feed outer aggregation, group/`HAVING`, `DISTINCT`, order/spill, and P3
   consumers through the same graph runner. Add safe child access selection.
4. **C4 — plans and hardening:** complete per-edge plans/counters, temporal and
   corruption precedence, allocation/retention evidence, checkpoint/reopen of
   base data followed by ad-hoc queries, and full compatibility migration.

No slice may leave both old and new nested engines reachable or retained.

## Acceptance gate

Promotion requires:

- parser precedence with sibling `EXISTS`/scalar/`IN`, descendants, quotes,
  escaped quotes, true depth, exact 8/31/32 bounds plus one, copy/reset/reuse,
  and every exclusion returning its documented status;
- scalar-subquery placement on either comparison side, child-local and
  ancestor-qualified projected expressions, global lexical marker order, and
  exact FNS evidence for child aggregate/group/distinct/order/P3 forms;
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
