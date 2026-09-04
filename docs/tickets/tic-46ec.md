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
deps:
    - tic-45a7
created: 2026-09-04T15:10:08.338824Z
---
# Reproduce and classify River stress-workload incompatibility

Reproduce the River INVALID_EXTERNAL_INPUT setup failure that currently
prevents river-harness from producing an eligible River stress artifact.

## Design

Run river-harness through riverd and trace parameterized insert and SELECT FOR
UPDATE admission through the shared logical workload and River boundary.
Distinguish harness defects from missing or incorrect River SQL behavior
without involving comparison tooling.

## Acceptance Criteria

The exact incompatible operations, expected semantics, owning repository,
status mapping, and minimum correction are evidenced; no River zero-TPS or
parity ratio is reported for an ineligible run.
