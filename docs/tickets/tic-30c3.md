---
id: tic-30c3
status: open
type: epic
assignee: blater
delivery: none
tags:
    - performance
    - tpcc
created: 2026-09-04T14:59:38.224581Z
---
# Reach competitive TPC-C transaction performance

Advance River from the restored 120–130 engineering TPS region through a credible 500 TPS interim milestone and toward the normative Alpha3/MariaDB parity gates.

## Design

docs/perf_review.md owns phase order and evidence gates; docs/plans/river-transaction-50x-optimization-route.md owns the architecture route; docs/performance-checkpoints.md owns accepted checkpoints.

## Acceptance Criteria

All child phase epics close with correctness, provenance, repeatable performance evidence, and no arbitrary production limits; final claims use declared workload denominators.
