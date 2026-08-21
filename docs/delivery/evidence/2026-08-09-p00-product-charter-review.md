# P00 product-charter review

Date: 2026-08-09

Status: passed after independent product and architecture review

## Accepted boundary

The [product charter](../../governance/product-charter.md) fixes River as a
general relational database with SQL, arbitrary transactionally maintained
indexes, constraints, multi-statement transactions, predictable OLTP latency,
and useful analytical scans. It deliberately delivers a recoverable
single-node product before adding one consensus group per database and durable
full-replica failover.

The charter also fixes the truth and durability boundaries that motivated the
project:

- SQL reads materialized heap, index, catalog, and MVCC state rather than
  compensating for apply lag by scanning the journal;
- local durable acknowledgement requires the local WAL contract;
- distributed durable acknowledgement requires a persistence quorum and
  leader publication;
- volatile quorum acknowledgement is explicitly non-durable, optional, and
  deferred behind its own measured gate; and
- general relational semantics are not traded for a TigerBeetle-style fixed
  command or schema model.

## Workload envelope

The independent review accepted the qualitative workload classes in the
[performance plan](../../plans/river-performance-review-and-benchmark-plan.md):

- RiverBank exercises OLTP contention, constraints, secondary indexes,
  rollback, and concurrent aggregation at named scale classes;
- RiverPapers exercises wide and variable relational data, index creation,
  joins, sorting, grouping, scans, spill, and streaming ingestion; and
- pathological generators exercise bounds and failure behavior.

P00 does not own numeric throughput, latency, allocation, copy, amplification,
queue, or recovery envelopes. P05 owns their hardware calibration. Requiring
P05 numbers for P00 would create a dependency cycle because P05 hard-depends
on P00.

## Independent verdict

The reviewer found no blocking, high, or medium contradiction between the
charter, implementation plan, ADR bundle, TigerBeetle comparison/decision
register, or replicated-journal plan. Proposed evidence-sensitive ADRs remain
owned by P01, P05, P06, P09, and later gates; they do not reopen the accepted
product scope.

P00 is therefore `passed`. P01, P05, P06, P09, M0, and G0 do not inherit that
status, and no milestone tag is authorized by this review.
