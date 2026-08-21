# ADR 0001: Product and SQL profile

Status: Accepted

## Context

River optimizes for high-throughput OLTP without becoming a fixed-operation
ledger. Its reason to exist is the combination of general relational
capability and a bounded, measurable storage kernel. A feature list alone does
not define compatible SQL behavior.

## Decision

The [product charter](../governance/product-charter.md) is the scope authority.
River is a general relational Java database with arbitrary transactionally
maintained indexes, multi-statement transactions, constraints, SQL/JDBC, and
meaningful analytical scans. The first supported product is a crash-safe
single-node database; replicated durability extends it after G4.

Q01 owns the versioned [SQL conformance profile](../compatibility/sql-conformance-profile.md).
Each entry states syntax, null/type/coercion behavior, transaction and error
semantics, SQLSTATE, limits, and a fixture. Unsupported syntax fails
explicitly. Historical Ingres behavior is not inherited unless the profile
deliberately selects it under the provenance policy.

## Invariants

- Heap rows, authoritative indexes, constraints, catalog, and MVCC effects
  share one transaction decision and visibility point.
- SQL reads materialized relational state and never query the WAL to hide lag.
- Optimizations may specialize a prepared path but cannot narrow the general
  SQL transaction contract.
- V1 support is the published profile, not parser acceptance or marketing text.
- Nested query blocks are supported through the profile's stated resource
  limit; their semantics do not change at an arbitrary nesting depth.
- Replication does not cause followers to rerun SQL planning, nondeterministic
  expressions, or constraint decisions.

## Consequences

River accepts broader correctness and performance work than a TigerBeetle-like
fixed operation service. The profile gives parser, binder, execution, JDBC,
and compatibility tests one semantic authority while allowing incremental
delivery.

## Alternatives

- A fixed operation vocabulary was rejected because it removes the stated
  relational objective.
- Accidental Ingres or full SQL-standard compatibility was rejected because it
  creates unreviewed behavior and an unbounded v1 surface.

## Required evidence

- Q01/U06 conformance fixtures, including nested-query, rejected, and limit
  cases.
- Transactional base/index/catalog atomicity tests.
- RiverBank and RiverPapers coverage under the
  [benchmark plan](../plans/river-performance-review-and-benchmark-plan.md).

## Authoritative context

- [Product charter](../governance/product-charter.md)
- [High-level plan](../plans/river-high-level-plan.md)
- [TigerBeetle comparison](../plans/river-tigerbeetle-comparison-and-recommendations.md)
