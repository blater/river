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

The one-worker `sample stock-level` workload reached 10,328.801 River
commits/s; a matched four-run comparison averaged 10,384.614 for River and
6,792.043 for MariaDB. Full cardinality is different: it loads 100,000
stock and about 300,000 order-line rows. Costed inner JOIN ordering at River
`34ee0800` raised the two-run full-profile mean from 23.732 to 1,135.756
commits/s, while a matched MariaDB control reached 4,848.308. This is
diagnostic workload evidence, not audited TPC-C. See the
[full-profile checkpoint](../performance-checkpoints.md#2026-09-28--full-stock-level-costed-inner-join-order).

The remaining full-profile gap is in the `order_line` scan and repeated
indexed `stock` probes. The current nested-loop path opens and closes a
tuple-index scan for each outer row, then fetches the base row. River already
has a direct exact primary-key fetch API; its use for joins needs the same
visibility and source-lock guarantees as the scan path.

## Delivery

- Use exact primary-key point fetches for eligible inner JOIN probes without
  changing SQL results, read-your-writes, isolation, lock protection, or
  failure cleanup.
- Profile root scanning, probe lookup, row decoding and transaction overhead
  after that change. Optimize the largest verified remaining costs through
  generic engine mechanisms, one feature checkpoint at a time.
- Keep the `sample` gain while making River faster than MariaDB on matched
  `full stock-level` runs. Continue improvements while targeted evidence
  shows a repeatable gain.

## Acceptance

Focused tests cover matching and missing keys, pending writes, READ COMMITTED
and SERIALIZABLE behavior, cancellation and scan cleanup. A clean full test
build passes. Use at least two identical samples per variant, then longer
interleaved full-profile River/MariaDB samples with matching eligible
comparison keys, successful invariants and zero failed or unknown outcomes.
Check the sample profile for regression. Record commands, versions, source
commits, artifacts and the decision in `docs/performance-checkpoints.md`.
