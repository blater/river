---
id: tic-9c58
status: open
type: epic
priority: 1
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - tpcc
    - mariadb
    - parity
    - alpha3
deps:
    - tic-c7bb
created: 2026-09-04T14:59:38.679044Z
---
# Parity gate: approach MariaDB and Alpha3 capacity

Make shared-harness River/MariaDB runs semantically eligible, quantify the gap by family and mechanism, and satisfy the normative relative and capacity gates.

## Design

Use identical manifests and paired/interleaved samples. The normative gate remains at least 1,000 committed TPS at the 95% lower bound, River median at least 80% of MariaDB, and qualifying-family p99 no more than 20% worse.

## Acceptance Criteria

Eligible comparisons have identical comparison keys and passing invariants; mechanism-specific gaps are resolved through explicit tickets; the final Alpha3 matrix and provenance requirements pass.
