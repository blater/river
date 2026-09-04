---
id: tic-46ec
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
    - harness
created: 2026-09-04T15:10:08.338824Z
---
# Reproduce and classify River harness incompatibility

Reproduce the River INVALID_EXTERNAL_INPUT setup failure that currently prevents an eligible MariaDB comparison.

## Design

Trace parameterized insert and SELECT FOR UPDATE admission through the shared logical workload and River boundary; distinguish harness contract defects from missing or incorrect River SQL behavior.

## Acceptance Criteria

The exact incompatible operations, expected semantics, owning repository, status mapping, and minimum correction are evidenced; no River zero-TPS or parity ratio is reported for an ineligible run.
