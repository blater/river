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

The remaining full-profile gap is dominated by repeated indexed `stock`
probes. An exact primary-key fetch candidate passed correctness tests but did
not produce a repeatable throughput gain, so it was not merged. A timing pass
measured about 0.40 µs per probe reloading the versioned index-root record,
0.63 µs opening the index cursor, and 0.84 µs fetching and decoding the wide
base row. Caching the root record at River `b17e0450` raised the mean of two
long full-profile runs from 939.516 to 1,204.348 commits/s; a later clean-build
run reached 1,334.778. See the [cache checkpoint](../performance-checkpoints.md#2026-09-28--cache-versioned-index-roots-across-join-probes).

## Delivery

- Reprofile the accepted cache build. Measure tuple cursor descent and
  base-row fetch on the unchanged JOIN.
- Test a generic projected unique-key lookup that returns needed values from
  one index search. Add index payload support if the measured gain justifies
  the storage and update cost. Preserve SQL results, read-your-writes,
  isolation, lock protection, and failure cleanup.
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
