# TPS score log

These are diagnostic River TPS samples collected during the regression search.
Each run used the same workload: `tiny`, standard mix, `no-wait-stress`, one
warehouse, ten terminals, batch rows 32, maximum attempts 32, one second
warmup, ten seconds measured, seed 42. The figures are engineering TPS, not
official TPC-C results or tpmC.

| Run time (minute) | Commit time (minute) | Commit | Subject | Build | Run | TPS | Retries | Errors | Assessment | Result artifact |
|---|---|---|---|---|---|---:|---:|---:|---|---|
| 2026-09-05 23:23 +0100 | 2026-09-05 10:12 +0100 | `705a0b2` | Merge current master into tic-5cc0 savepoint resources | passed | OK | 74.600 | 0 | 0 | Outlier; superseded by local repeat | `/private/tmp/river-tps-results/q3-solo/705a0b2da537b8b3edf46faf4464259b4ca01a44` |
| 2026-09-05 23:34 +0100 | 2026-09-04 14:42 +0100 | `adccf71` | recovery: preserve pre-launcher 120 TPS checkpoint | passed | OK | 123.900 | 0 | 0 | Stable control | `/private/tmp/river-tps-results/local-control/adccf7172e74450cf4518a561b3712c4e8927c0` |
| 2026-09-05 23:35 +0100 | 2026-09-04 23:06 +0100 | `3780b5c` | Merge latest master into tic-af29 candidate | passed | OK | 124.600 | 0 | 0 | Healthy | `/private/tmp/river-tps-results/local-control/3780b5c19e743b7a9396dcfb5d811541d8bd10f5` |
| 2026-09-06 00:18 +0100 | 2026-09-05 09:55 +0100 | `5576884` | fix(engine): honor store fence during transaction admission | passed | OK | 108.100 | 0 | 0 | High-variance sample; repeated below | `/private/tmp/river-tps-results/local-control/557688456bf9678589559ee21204bef58cb2bac2` |
| 2026-09-06 00:43 +0100 | 2026-09-05 10:08 +0100 | `1da80d4` | fix(sql): account retained savepoint capacity | passed | OK | 121.800 | 0 | 0 | Healthy | `/private/tmp/river-tps-results/local-control/1da80d48a13836546c2befc15febccbd5d4a5136` |
| 2026-09-06 01:18 +0100 | 2026-09-05 10:06 +0100 | `e9e5583` | Merge tic-e2be spill-boundary prerequisite | passed | OK | 127.600 | 0 | 0 | Healthy | `/private/tmp/river-tps-results/local-control/e9e558398d19786a795abdc3c0331b990f2faf8a` |
| 2026-09-06 01:20 +0100 | 2026-09-05 09:55 +0100 | `5576884` | fix(engine): honor store fence during transaction admission | passed | OK | 125.400 | 0 | 0 | Repeat control; healthy | `/private/tmp/river-tps-results/local-control/557688456bf9678589559ee21204bef58cb2bac2-retry` |
| 2026-09-06 01:22 +0100 | 2026-09-05 10:12 +0100 | `705a0b2` | Merge current master into tic-5cc0 savepoint resources | passed | OK | 128.800 | 0 | 0 | Local confirmation; healthy | `/private/tmp/river-tps-results/local-control/705a0b2da537b8b3edf46faf4464259b4ca01a44-retry` |
| 2026-09-06 01:55 +0100 | 2026-09-05 22:22 +0100 | `ab7ffd0` | Merge latest master with ticket scope updates | passed | OK | 129.600 | 0 | 0 | Integrated HEAD; healthy | `/private/tmp/river-tps-results/local-control/ab7ffd02d781cce216f9a8f7be4d6fa089449cf3` |

The `5576884` pair spans 108.100–125.400 TPS, so the first result is treated
as host variance. The `705a0b2` pair spans 74.600–128.800 TPS, so the first
result is also treated as host variance. No reproducible numerical TPS
regression has been established by these samples.

## Invalid attempt

| Run time (minute) | Commit | Build | Run | TPS | Reason | Evidence |
|---|---|---|---|---:|---|---|
| 2026-09-06 01:25 +0100 | `8290dd3` | passed | evidence-invalid | — | Provenance runner observed Gradle activity during its own build; no workload score was published | `/private/tmp/river-tps-results/local-strict/8290dd313be686fbee37fd09e9daa5ffb2d99747-retry` |

| Change | Commit | Ticket | Branch | Date/time | TPS result |
|---|---|---|---|---|---:|
| Remove unconditional TPS gate | `ab7ffd0` | `tic-0636` | `ticket/tic-0636-remove-attestation` | 2026-09-06 02:05 +0100 | 129.600 TPS (latest valid HEAD sample) |
| Move TPS build into standalone `make.sh` | `ab7ffd0` | `tic-0636` | `ticket/tic-0636-remove-attestation` | 2026-09-06 02:20 +0100 | 93.000 TPS (3-second no-build harness sample) |
| Update no-build provenance coverage | `ab7ffd0` | `tic-0636` | `ticket/tic-0636-remove-attestation` | 2026-09-06 02:26 +0100 | 93.000 TPS (same no-build harness sample) |
