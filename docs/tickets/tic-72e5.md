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

The accepted root filter, numeric JOIN text-copy pruning, inline exact
`COUNT(DISTINCT)` set, singleton block row store, and validated root row
filter raised the measured SQL-path River baseline to 5,018.188 commits/s.
A generic one-request transaction program then raised River's latest
measured Stock Level baseline to 10,328.801 commits/s. The final matched
MariaDB–River–River–MariaDB pair averaged 6,792.043 versus 10,384.614
commits/s: River was 1.529x faster for this one-worker workload. The
whole-transaction gap is closed. The separately timed count JOIN still
cost about 101 µs in River versus 56 µs in MariaDB on the SQL path;
JOIN stage startup, scan and prepared-query costs remain under
investigation. See [the latest checkpoint](../performance-checkpoints.md#2026-09-28--stock-level-read-program-checkpoint).

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
