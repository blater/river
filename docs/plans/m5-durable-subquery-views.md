# M5 durable subquery view delivery plan

Status: approved architecture plan; implementation follows online schema
evolution and consumes the completed n-table JOIN and P4C architectures.

Owner: SQL/catalog integration lead. Promotion requires independent relational
semantics and durable-format/compatibility review.

## Outcome

River shall persist and reopen a view whose admitted query graph contains P4C
scalar, `EXISTS`, or membership subqueries, including computed correlation and
n-table join roles. The stored view executes through the same canonical query
graph, binder, subquery runner, join source, and P3 pipeline used by the ad-hoc
statement. There is no stored-view-specific truth engine or flattened query.

The completed slice admits direct and derived/cardinality view definitions
whose complete physical graph remains within the existing bounds: 32 query
blocks, 31 subquery edges, eight relation roles per block, and 32 physical role
occurrences across the graph.

## Dependencies and exclusions

This plan starts only after:

- the n-table plan has installed catalog view v4 and exact ordered lineage;
- P4C has one canonical graph and common recursive runtime; and
- online schema evolution can atomically replace a view and swap dependencies.

View definitions contain no parameters and cannot correlate to a scope outside
the stored definition. Inside the definition, every P4C lexical correlation,
n-table child/ancestor scope, scalar/`EXISTS`/`IN` edge, projection,
aggregation, grouping/`HAVING`, `DISTINCT`, order, and bounded spill form
already admitted for ad-hoc SQL is eligible.

Still excluded are row-valued/`ANY`/`ALL`/lateral/recursive SQL, joined DML,
unsupported join-tree forms, and any query exceeding the established P4C or
join bounds. Exclusions return `FEATURE_NOT_SUPPORTED` or the existing exact
bound status at CREATE time.

## Canonical lineage

Catalog view v4 owns up to 32 ordered physical table IDs. Durable subquery views
use one deterministic traversal:

1. visit the canonical query graph depth first from the stored root;
2. within each block, visit relation roles in SQL occurrence order; and
3. append the resolved physical table ID for every role occurrence.

The list records role occurrence, not a sorted dependency set. Repeated IDs are
valid for explicitly aliased self-join roles. Dependency operations derive a
set while scanning the ordered list, so each distinct physical object is
protected once.

The stored SQL remains the semantic source. Lineage proves that reparsing names
has not rebound the definition to different physical objects; it is not a
serialized execution plan.

## CREATE and replace boundary

`CREATE VIEW` and `CREATE OR REPLACE VIEW` perform the complete trust-boundary
sequence before catalog mutation:

1. parse the entire definition and validate graph/join/expression bounds;
2. resolve every role and bind all Boolean/scalar/projection programs against
   one catalog snapshot;
3. validate stored-safe zone names, text, aliases, result metadata, and every
   dependency;
4. capture canonical ordered lineage and normalized result descriptors/
   nullability; and
5. atomically insert or replace SQL text, lineage, dependency edges, and result
   metadata.

No child query executes during CREATE. Row-time cardinality, DST, arithmetic,
or data-dependent constraint outcomes remain execution outcomes. Invalid SQL,
names, types, zones, or lineage capacity fail before the old definition is
changed.

Replacement preserves the logical view object ID while atomically swapping its
query text and dependency set as defined by the schema-evolution plan. Existing
transactions retain the old schema epoch; newly admitted statements bind the
new definition.

## Reopen and execution boundary

Expansion reads and strictly decodes v4, reparses the SQL, rebuilds the
canonical graph, resolves all physical roles, and requires exact lineage count
and ordered-ID equality. It also verifies stored result descriptor/nullability
metadata against the rebound projection. Any checksum-valid mismatch,
unsupported persisted topology, malformed zone/text, missing dependency, or
semantic drift is `CORRUPTION`, never user-facing `FEATURE_NOT_SUPPORTED` and
never silent rebinding.

After validation, append/compose the stored graph through the ordinary query
compiler. The deepest physical source filters through P4C exactly once and
feeds the existing join/P3 consumers. Point and streaming execution use the
same path as equivalent ad-hoc SQL. Temporal/current values are captured once
per executing statement; persisted definitions do not store or replay resolved
clock values.

Plain `EXPLAIN` validates/expands without executing child edges. `ANALYZE`
reports the same joined stages, subquery edges/cache behavior, and P3 row counts
as the equivalent ad-hoc definition plus the outer view consumer stages.

## Ownership, lifecycle, and resources

Expansion reuses the session's parser, `SqlBinder`, block-plan binder,
join source, subquery graph runner, and P3 stores. It may retain one bounded
`ViewDefinition` and SQL text buffer already owned by the persisted compiler;
it must not retain another full query/binder/evaluator graph.

All blocks and temporal programs are bound/prepared before any source opens.
Runtime error and cleanup precedence is unchanged from P4C: no outer/view row
publishes before inner work succeeds, child cursors close inner-to-outer,
failed cleanup remains retryable, and cached/owned text is erased after
successful close. Repeated view execution is allocation-free per row and uses
the warmed parser/query owners.

## Delivery slices

1. **V1 — create-time admission:** generalize stored-view policy and validator
   from one deepest join to the complete canonical graph; capture v4 lineage
   and result metadata; retain exact fail-closed exclusions.
2. **V2 — persisted expansion:** reparse/rebind the complete graph, compare
   exact lineage/metadata, and compose it through direct and P3 consumers.
3. **V3 — dependency and schema integration:** protect every distinct lineage
   object; support atomic replace/rename from schema evolution; prove stale
   epoch behavior and failed replacement preservation.
4. **V4 — durability and hardening:** checkpoint/WAL reopen, backup/restore,
   corruption, plan truth, spill, temporal/status cleanup, and allocation gates.

No slice admits stored subquery SQL unless the same commit has its strict
decode/reopen path.

## Acceptance gate

Promotion requires:

- direct correlated `EXISTS`, scalar, and `IN` views plus multiple siblings and
  a three-level descendant;
- n-table joined ancestors and children, mixed `LEFT JOIN` NULL extension, raw
  and computed correlation, and exact nullable result metadata;
- scalar/grouped aggregate, `HAVING`, `DISTINCT`, ordering, and 1,025-row
  Unicode spill through stored expansion with exact values/types;
- statement-stable current/zone behavior, eager invalid-zone rejection at
  CREATE/reopen, lazy DST/cardinality failure at execution, terminal repeat,
  close/reuse, and no partial outer row;
- exact role-ordered lineage including repeated self-join IDs, rename/drop/
  type-change conflicts for every distinct dependency, and atomic dependency
  swap on replacement;
- checkpoint base plus WAL-created view reopen, offline backup/restore, and
  equivalent point/stream results after restore;
- checksum-valid wrong/swapped/missing/extra IDs, invalid counts/unused slots,
  malformed UTF-8, semantic SQL drift, missing objects, and result-metadata
  mismatch returning `CORRUPTION` without deleting the record;
- exact plain and analyzed graph/join/P3 plans and counters;
- warmed repeated execution within the existing SQL allocation budget and no
  duplicate retained query/binder/store graph; and
- full SQL/engine/backup suites, design-debt/diff checks, and independent
  relational-semantics plus catalog-format review.

## Stop conditions

- Stored SQL and v4 lineage are the only durable semantic/identity sources; no
  serialized physical plan.
- Ad-hoc and stored definitions execute through exactly one graph/join/P3 path.
- Persisted trust failures are `CORRUPTION`; user CREATE validation failures keep
  their ordinary SQL status.
- No per-row allocation, no duplicate general binder, and no second recursive
  evaluator or row store.
- No new owner above design-debt score 100 and no touched owner more than five
  points above its accepted baseline.
