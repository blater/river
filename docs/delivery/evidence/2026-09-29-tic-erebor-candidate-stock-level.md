# Erebor clustered-row Stock Level diagnostic

Date: 2026-09-29 UTC. Ticket: [tic-erebor](../../tickets/tic-erebor.md).
The stable control is `origin/master` `2ada6350` in
`/private/tmp/river-ent-candidate/river`. The candidate is branch
`feature/tic-erebor-clustered-row-store` at `9f7684b6`, assembled in
`/private/tmp/river-erebor-dist/river` from its format, storage and engine
JARs and the unchanged control distribution's other JARs. Candidate SHA-256:
format `fc128bc9e17726a9a4afcf1faa959e306cb506cb6dab04818dd1564cfb9007ce`,
storage `261b53e581f69099c3912926206e5255cf8390afd34ea9447dde17ae49ad2d16`,
engine `fa3be86f96edc116704ef399419e5e4f0cccf42e22bc18c3b9373c1c86ed5ea1`.
The harness is revision `5082670`; runtime is GraalVM 25.0.4 JVM `-Xmx1g`
on macOS/arm64, READ COMMITTED, local durable WAL and loopback TCP/TLS.
Both executables were frozen before these serial runs; no Gradle build or
other harness workload overlapped them.

Each command used `benchmark run river tpcc full stock-level`, one warehouse,
one worker, seed 42, retry limit 3, and the installed `--river-executable`
above. Short runs used `--warmup=5s --duration=20s`; longer runs used
`--warmup=10s --duration=60s`. Each supplied the version label in the table
using `--river-version`. Artifacts are immutable directories under
`/Users/blater/src/ingres/river-harness/runs/`.

| Order | Version label | TPS | p99 ms | Artifact |
| --- | --- | ---: | ---: | --- |
| Short 1 | `erebor-candidate-e1` | 1,470.727 | 0.851 | `river_harness_20260929_144725_40c521ba` |
| Short 2 | `erebor-control-b1` | 1,208.132 | 1.129 | `river_harness_20260929_144931_db6ea4d9` |
| Short 3 | `erebor-candidate-e2` | 1,195.329 | 1.170 | `river_harness_20260929_145154_bb52e6fe` |
| Short 4 | `erebor-control-b2` | 1,226.331 | 1.108 | `river_harness_20260929_145435_6cdb63a3` |
| Long 1 | `erebor-control-long-c1` | 1,291.724 | 1.040 | `river_harness_20260929_145646_4cbf1dc6` |
| Long 2 | `erebor-candidate-long-e1` | 1,334.272 | 0.928 | `river_harness_20260929_145936_43932516` |
| Long 3 | `erebor-candidate-long-e2` | 1,249.105 | 1.002 | `river_harness_20260929_150258_0ad61dcf` |
| Long 4 | `erebor-control-long-c2` | 847.209 | 2.767 | `river_harness_20260929_150618_72466b49` |

All eight reports passed their full-profile invariants, reported zero failed
or unknown outcomes and zero retries, and completed graceful owned-instance
shutdown. Each cancelled one worker attempt at the measured-window boundary.
Each short report has the same eligible comparison key
`fd5865b1664a105b7d0118301ac698f9fd29099f34a871b9a4e4e3410344eb8f`;
each long report has eligible key
`f7b5a6af0cf208ccf907fb1d6fc0585a508a22c80c4fb44003ea3c52b783c1f9`.
The second short candidate was below both adjacent short controls. The second
long control fell well below the preceding runs. These are whole-workload
diagnostics with substantial host variation, not an isolated read-path CPU
measurement or a general speedup claim. The user accepted these numbers as
sufficient for the performance decision on 2026-09-29; correctness and
durable-format completion remain separate gates before promotion.

The candidate measured here precedes overflow retirement recording commit
`297f62dd`. Stock Level's `order_line` and `stock` rows are inline under the
ADR placement rule, so that subsequent overflow-only change is outside this
workload's exercised path. No New Order, Payment, order-status or mixed
candidate report has yet been captured. The identity-index write and
history costs, production CPU attribution and occupied-page distribution
remain unmeasured.
