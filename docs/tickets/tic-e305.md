---
id: tic-e305
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-9c58
delivery: evidence
tags:
    - performance
    - tpcc
    - comparison
    - sidecar
    - migration
deps:
    - tic-61c2
created: 2026-09-04T16:47:29.409943Z
---
# Verify comparison extraction into an independent sidecar

Track removal of comparison implementation from river-harness and delivery of the independent artifact-consuming sidecar in their owning repositories.

## Design

The sidecar owns eligibility, pairing, statistics, confidence, ratios, and comparison reports. river-harness owns target execution and versioned artifacts only. Each repository uses its own ticket, branch, tests, commit, and release; this River ticket records external immutable references.

## Acceptance Criteria

Linked sidecar and river-harness deliveries remove compare commands, packages, thresholds, and reports from the harness; dependency checks prove no imports of River or harness internals in the sidecar; contract fixtures and rejection cases pass across independently versioned processes.
