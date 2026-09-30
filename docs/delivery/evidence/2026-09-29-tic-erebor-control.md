# Erebor control before clustered-row implementation

Ticket: [tic-erebor](../../tickets/tic-erebor.md). The source is pushed
`origin/master` `2ada6350`; its production engine JAR SHA-256 is
`9952756dec648338e009fc3ef47dc9ec133020fff30143e6e6c624146ade2b12`.
The executable is `/private/tmp/river-ent-candidate/river`, with that JAR in
its distribution. Harness revision: `5082670`. Host: macOS/arm64. Runtime:
GraalVM 25.0.4, `-Xmx1g`. Both runs used READ COMMITTED, local durable WAL
and loopback TCP/TLS.

The control command was run serially twice on 2026-09-29 UTC, changing only
the `--river-version` label from `erebor-control-a1` to `erebor-control-a2`:

```sh
~/src/ingres/river-harness/benchmark run river tpcc full stock-level \
  --river-executable=/private/tmp/river-ent-candidate/river \
  --river-version=erebor-control-a1 --warmup=5s --duration=20s \
  --workers=1 --warehouses=1 --seed=42 --max-retries=3
```

| Label | Committed TPS | p99 ms | Immutable report |
| --- | ---: | ---: | --- |
| `erebor-control-a1` | 1,379.035 | 0.996 | `river_harness_20260929_110148_43061bc7` |
| `erebor-control-a2` | 1,285.677 | 1.064 | `river_harness_20260929_110339_55ac34f3` |

Reports are under `/Users/blater/src/ingres/river-harness/runs/`. Both have
`status: passed`, eligible identical comparison key
`fd5865b1664a105b7d0118301ac698f9fd29099f34a871b9a4e4e3410344eb8f`,
zero failed/unknown outcomes and retries, and passed full-profile invariants.
Each has one worker cancellation at the measured-window cutoff and no
unreconciled outcome. The harness used `river-stop` for graceful owned-instance
shutdown and found the service inactive afterward. These unpaired controls
show approximately 7% TPS variation and designate no new baseline.

The same production JAR was used for the earlier
[Thranduil mechanism counters and JFR profile](2026-09-29-tic-thranduil-indexed-probes.md).
That pass measured about 225 candidates in each primary table, 451 logical-head
lookups, 472 heap fetches and 2,783 page-pin calls per Stock Level transaction,
with zero metadata or indexed-page file reads after warmup. It counted a
35.5 kB projected retention copy per transaction and attributed worker samples
to tuple comparison, traversal, row fetch and head lookup. Those diagnostic
samples are inherited control-path evidence, not a candidate comparison;
their source edits and JFR remain at the paths named in that report.

The untouched-source slopmark scan of `river-format`, `river-storage` and
`river-engine` main Java directories is retained in the pickup session. The
highest relevant score was `IndexedPageFrameCache` 245.463; changes to this
class require extra responsibility review. The scan also reported incomplete
shallow coverage for some files, so its score is a design signal only.
