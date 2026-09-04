---
id: tic-a133
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-9c58
delivery: evidence
tags:
    - performance
    - tpcc
    - mariadb
    - parity
    - alpha3
    - promotion
deps:
    - tic-7ec5
    - tic-630d
created: 2026-09-04T15:10:08.617995Z
---
# Run the normative MariaDB parity and Alpha3 campaign

Execute the final scaling and comparison matrix only after the 500 TPS checkpoint and all mechanism tickets admitted by the parity baseline are complete.

## Design

Use the exact Alpha3 workload, durability, isolation, scale, ten-sample, transaction-count, provenance, absolute throughput, relative median, family latency, invariant, and failure criteria in docs/perf_review.md.

## Acceptance Criteria

The 95% lower bound is at least 1,000 committed TPS, River median is at least 80% of MariaDB, qualifying-family p99 is no more than 20% worse, all outcomes reconcile, and the accepted checkpoint is pushed and tagged.
