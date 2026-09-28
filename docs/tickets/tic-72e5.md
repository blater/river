---
id: tic-72e5
status: open
type: performance
priority: 1
assignee: blater
delivery: code
tags:
    - sql
    - joins
    - stock-level
created: 2026-09-28T01:59:00Z
---
# Close the Stock Level join performance gap

The one-worker sample Stock Level baseline at `c9c216d3` was 1,065–1,099
commits/s for River and 5,981–5,996 for MariaDB. The count query accounted
for 81% of the measured transaction-time difference. River joined about 207
`order_line` rows before applying the stock quantity filter; MariaDB selected
stock first and performed about 45 indexed order-line lookups.

## Delivery

- Evaluate safe root-only `WHERE` conjuncts before JOIN probes in both River
  join executors, preserving SQL three-valued logic, outer joins and errors.
- Use the same stock-first SQL order in the standalone harness when it is the
  faster safe choice. Keep the River engine free of workload-specific rules.
- Measure the remaining count-query and transaction time. Continue with one
  generic mechanism per feature branch, using actual plan counters and matched
  River/MariaDB runs. Stop when the measured gap is closed or a concrete
  remaining cost is isolated and assigned a separate ticket.

## Acceptance

Focused SQL tests cover rejected root rows, `AND`/`OR`, left joins, aggregate
output and an expression-error boundary. A clean full test build passes. Two
identical samples per variant pass validation, accounting and cleanup; a
longer interleaved comparison resolves host variation. Record commands,
versions, immutable artifacts, plan counts and the decision in
`docs/performance-checkpoints.md`.
