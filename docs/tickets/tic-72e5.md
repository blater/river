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

The full-profile harness supplies separate River and MariaDB DDL catalogues.
River now reuses an existing index when its leading columns support a foreign
key. A diagnostic post-load inventory found matching table, uniqueness and
ordered-column shapes for all 15 workload indexes in each target, excluding
MariaDB's harness ownership table; see the
[parity checkpoint](../performance-checkpoints.md#2026-09-28--foreign-key-support-index-reuse-and-schema-parity).
The harness does not yet enforce that inventory as a comparison eligibility
rule. The earlier MariaDB number therefore remains a diagnostic control.
Do not add a Stock Level-specific index to the benchmark schema to close the gap.

Skipping publication of VARCHAR values proved unused by a generic JOIN raised
the mean of two interleaved 30-second `full stock-level` runs from 1,205.110
to 1,247.589 River commits/s against adjacent controls. The clean full test
build and a one-worker `sample all` run passed; the adjacent `sample
stock-level` candidate also exceeded its control. See the
[text-publication checkpoint](../performance-checkpoints.md#2026-09-28--omit-unused-text-publication-during-joins).
This is a small local gain, not a new MariaDB comparison. A projected
stock-key lookup and lower base-row fetch/decode cost remain the larger targets.

A later sampled timing pass found roughly 225 indexed `stock` opens per full
Stock Level count query and placed most query time in JOIN row production.
Stopping a proven unique inner cursor after one candidate and limiting the
per-root stage reset to active JOIN stages both passed focused tests, but
neither produced a repeatable full-profile throughput gain. Their source was
not accepted. A fresh independent MariaDB run reached 4,671.164 TPS against
the accepted River baseline of 1,234.227 TPS, a rough River/MariaDB ratio of
0.264; the runs were not interleaved. See the
[residual JOIN diagnosis](../performance-checkpoints.md#2026-09-28--residual-full-stock-level-join-diagnosis).

## Delivery

- Reprofile the accepted cache build. Measure tuple cursor descent and
  base-row fetch on the unchanged JOIN.
- Define one canonical workload schema/index contract and verify the actual
  primary and secondary index columns on each target after schema creation.
  Account for automatically created foreign-key indexes and reject comparisons
  when the resulting index sets differ.
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
Require matching actual index inventories before promoting a cross-database
performance result.
Check the sample profile for regression. Record commands, versions, source
commits, artifacts and the decision in `docs/performance-checkpoints.md`.
