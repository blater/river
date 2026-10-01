---
id: tic-curufin
status: open
type: investigation
priority: 2
assignee: blater
delivery: evidence
tags:
    - dry
    - refactor
    - architecture
deps:
    - tic-damrod
links:
    - tic-damrod
created: 2026-10-01T15:07:48.444484Z
---
# Evaluate broader SQL and result-lifecycle consolidation after the WET refactorings

## Description

Evaluate the broader DRY opportunities identified while implementing
[tic-damrod](tic-damrod.md). That ticket consolidates matching responsibilities
in the [WET catalog](../wet.md); the opportunities below require decisions about
live execution paths, public contracts, or ownership across several lifecycles.
They are candidates, not established defects or promised performance gains.

### Opportunities

| Priority | Opportunity and source evidence | Potential change and why it is wider | Required decision and coverage |
| --- | --- | --- | --- |
| P1 | **One SQL ordering/grouping execution contract (WET-08).** [`SqlLegacySortTupleLayout`](../../river-engine/src/main/java/io/riverdb/engine/sql/SqlLegacySortTupleLayout.java) is used by [`SqlLegacyGroupTupleComparator`](../../river-engine/src/main/java/io/riverdb/engine/sql/SqlLegacyGroupTupleComparator.java), which is used by [`SqlSortWorkspace`](../../river-engine/src/main/java/io/riverdb/engine/sql/SqlSortWorkspace.java), run sorting, and spill comparators. [`SqlDescriptorSetOrdering`](../../river-engine/src/main/java/io/riverdb/engine/sql/SqlDescriptorSetOrdering.java) configures [`SqlDescriptorSetShape`](../../river-engine/src/main/java/io/riverdb/engine/sql/SqlDescriptorSetShape.java) for descriptor set execution. | Both paths are active. Legacy sorting resolves aliases and column names with case folding; descriptor ordering uses exact output names and descriptor grouping expressions. Legacy retained tuples and descriptor set storage have different layouts and consumers. Replacing one path requires migrating binding, grouping, retained sorting, and spill consumers together rather than deleting a similarly named helper. | Trace routing from SQL admission to both paths; state required alias, case, duplicate-lane, NULL, direction, grouping, and spill semantics. Select a single owning contract only if it can replace a live path completely. Exercise real queries, grouped output, spill, resource pressure, and cleanup. Coordinate any migration with existing SQL execution tickets. |
| P2 | **One JDBC metadata cursor-state owner (WET-09).** [`RiverColumnsResultSet`](../../river-jdbc/src/main/java/io/riverdb/jdbc/RiverColumnsResultSet.java), [`RiverCatalogResultSet`](../../river-jdbc/src/main/java/io/riverdb/jdbc/RiverCatalogResultSet.java), [`RiverIndexInfoResultSet`](../../river-jdbc/src/main/java/io/riverdb/jdbc/RiverIndexInfoResultSet.java), and [`RiverPrimaryKeyResultSet`](../../river-jdbc/src/main/java/io/riverdb/jdbc/RiverPrimaryKeyResultSet.java) each maintain row availability, completion, closure, last-value-read state, and NULL state. | Tic-damrod shares matching operations through `RiverMetadataResultSet`. Sharing the remaining state requires reconciling materialized catalog iteration, per-relation description queries, index rows, and primary-key parts. [`RiverGeneratedKeysResultSet`](../../river-jdbc/src/main/java/io/riverdb/jdbc/RiverGeneratedKeysResultSet.java) additionally has statement ownership and different typed conversions; folding it in would broaden the public lifecycle change. | Decide whether a cohesive cursor-state component actually removes complexity. Specify next/end/close transitions, empty results, row numbering, `wasNull`, connection notifications, close failures, and generated-key conversion differences. Test all real result-set families before choosing implementation scope. |
| P2 | **One public result-value access contract (WET-12).** [`RowResult`](../../river-engine-api/src/main/java/io/riverdb/engine/api/RowResult.java) and [`CommandResult`](../../river-engine-api/src/main/java/io/riverdb/engine/api/CommandResult.java) expose matching accessors backed by [`PublicResultValues`](../../river-engine-api/src/main/java/io/riverdb/engine/api/PublicResultValues.java). | The semantic value owner is already shared. Removing the remaining facade methods would introduce a public common interface or hierarchy and migrate engine, client, protocol, JDBC, and user-facing callers. `RowResult` has metadata reservation generations and stream availability; `CommandResult` has transaction, commit, affected-row, and optional-row state. Combining their lifecycles solely to reduce forwarding can increase coupling. | Compare a small public read-only value contract with keeping two concrete facades. Preserve result validity, metadata reservation, reset, retained-memory charging, text access, release, and command completion semantics. Show a reduction in total complexity and keep implementation types out of the public contract. Retain the facades if the shared API brings no concrete consumer benefit. |
| P3 | **A simpler primitive paged-container owner (WET-17).** [`PagedIntArray`](../../river-engine/src/main/java/io/riverdb/engine/table/PagedIntArray.java), [`PagedLongArray`](../../river-engine/src/main/java/io/riverdb/engine/table/PagedLongArray.java), and [`PagedBooleanArray`](../../river-engine/src/main/java/io/riverdb/engine/table/PagedBooleanArray.java) have similar lazy allocation loops through [`IndexedPagedArrayAllocator`](../../river-engine/src/main/java/io/riverdb/engine/table/IndexedPagedArrayAllocator.java). | Integer metadata admits index zero, while row-ID long/boolean metadata excludes it. Each allocator returns a different primitive array, and clear/failure handling acts on that type. A generic extraction would introduce erased storage, casts, callbacks, or a new container hierarchy unless there is a concrete simpler representation. | Identify actual consumers and fault-injection providers. Compare complete owner/caller code, allocation behavior, bounds, short/null allocator results, OOM translation, and clear semantics. Implement only if a primitive-preserving design is simpler overall; accepting the current typed duplication is a valid evidence-based outcome. |

## Design

This follow-up first delivers decisions. For each accepted opportunity, define
a separate implementation slice with one observable outcome, one canonical
owner, complete caller migration, and explicit non-goals before changing code.
Use the delivered tic-damrod base and recheck these source observations.

Do not turn this into a general codebase cleanup, combine all four opportunities
into one architecture rewrite, or assume that common syntax requires a common
lifecycle. Keep distinct contracts where consolidation would obscure ownership.

## Acceptance Criteria

- [ ] Each opportunity has a current-source consumer/lifecycle trace and an
  explicit implement-or-retain decision with supporting evidence.
- [ ] Accepted refactorings have bounded implementation tickets, required
  semantic and failure coverage, and ownership coordinated with existing work.
- [ ] Rejected extractions explain the distinct contracts or added complexity;
  no unsupported performance benefit is claimed.
- [ ] The decisions link immutable evidence before this investigation closes.

