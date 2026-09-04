---
id: tic-638b
status: open
type: story
assignee: blater
parent: tic-5db4
delivery: code
tags:
    - p0
    - performance
    - diagnostics
    - tpcc
deps:
    - tic-8ef7
created: 2026-09-04T19:40:37.542856Z
---
# Make native P0 evidence phase-exact and cardinality-correct

Make the native TPC-C evidence path capable of proving the existing P0 correctness gate before the promotion matrix runs.

## Design

Keep lock diagnostics generic and byte-admitted. Separate preflight, warmup, measured, and drain epochs; model attempt lifecycle from server transaction outcome to one client disposition; distinguish victim selections, victim outcomes, queued requests cancelled, and retries; emit existing resource identities; attest effective isolation. Do not change lock policy, retry policy, isolation semantics, durability, or throughput behavior.

## Acceptance Criteria

Enabled valid diagnostics are mandatory for a P0 evidence run; phase totals and all four cardinalities reconcile independently; reused, missing, duplicate, or unmatched attempt IDs invalidate the run; program steps and effective JDBC/program isolation are observable; one victim with multiple queued requests proves one victim disposition; focused tx/engine/JDBC/bench/tool tests and affected module tests pass; no arbitrary event cap is introduced outside an admitted byte budget.
