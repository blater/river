# Erebor three-retry mixed workload resolution

Date: 2026-09-29 UTC (Europe/London: BST, UTC+1). Ticket: `tic-erebor`.
River branch: `feature/tic-erebor-clustered-row-store`. Production engine source
remains `4e08034d`; the measured checkout is `5cfefeeb`. SQL proof: `0b68064f`.
Harness branch: `fix/erebor-three-retry-mix`, source `356682a`, in
`/private/tmp/river-harness-erebor-retry`, based on `5082670`.

## Cause and owning correction

The [previous failed reports](2026-09-29-tic-erebor-write-cost.md#four-worker-mixed-retry-boundary)
exhausted New Order stock deadlock retries on both stable and Erebor builds.
The shared full SQL binding visited stock in the generated random line order.
Different districts can concurrently retain exclusive locks on different stock
rows and then request each other's rows. Sample data has only 100 items.

`SqlGeneralConcurrencyTest.readCommittedLockingReadsRequireConsistentRowOrder`
uses real READ COMMITTED SQL, `FOR UPDATE` and scheduler admission to reproduce
the opposing-order cycle. Diagnostics identify two exclusive active-owner edges
on different logical-row resources, one victim, one cancelled request and
complete victim cleanup. Its adjacent consistent-order control waits, reads
the latest committed row after handoff, commits both updates and retains zero
transactions, snapshots, holdings or requests. This confirms a real dependency
cycle; the engine's grant and victim policies are unchanged.

The harness's existing `sqlfull.fullExecutor` now reuses an index workspace
and visits stock in ascending `(SupplyWarehouseID, ItemID)` order. It processes
each line using its original input index, preserving `ol_number`, item,
warehouse, quantity, amount and district information. Equal stock keys retain
their original relative order. Generated input remains immutable across whole
transaction retries. The original final invalid item still triggers atomic
rollback even when its sorted visit precedes a remote line. There are no extra
SQL statements or stock reads, and the workspace grows with the input rather
than imposing a new cardinality cap.

Both full SQL bindings use this policy. River is `tpcc-full-river-v5`; MariaDB
is `tpcc-full-mariadb-v2`. Their binding digests include
`tpcc-new-order-stock-key-order-v1`; admission records the stock-order deviation.
The accepted MariaDB New-Order-only reference is unchanged. The earlier v4
failed and ten-retry reports remain retained under their original identities.
They have not been relabelled as successful three-retry runs. New measurements
use the corrected binding identity, so they are not comparisons against the
previous access-order policy.

The correction resides in the independently owned harness. River production
contains no workload-specific sorting, retry changes, additional locks,
identity-map work or diagnostics. The [physical write-cost measurements](2026-09-29-tic-erebor-write-cost.md)
and accepted Stock Level decision retain their original scope.
The owner's subsequent request to resolve the three-retry workload is delivered
as this separate harness defect correction. Erebor's original scope excludes
general harness/comparator development; its row-store mechanism, workload SQL,
schema/index inventory and promotion boundary are unchanged.

## Validation

- Harness `go test ./...`, `go test -race ./...`, `go vet ./...` and `make build`
  pass. Focused driver tests verify emitted stock/update/line parameters,
  remote counters, original final-item rollback, unexpected missing-item
  rejection, retrying unchanged input, stable equal-key order and workspace
  growth/reuse.
- Serial `:river-engine:check` passes in 3m 3s: all **1,137 tests**, zero
  failures, errors or skips. Production is unchanged from the previous clean
  full checkpoint; this adds the SQL concurrency proof.
- Slopmark `transaction_new_order.go`: 20.9653 → 27.7461. The increase prompted
  a responsibility check: visitation order remains in the existing common
  SQL binding, with no policy in the generic scheduler or River engine.
  `transactions.go` remains 37.9643. SHALLOW coverage is incomplete.

Logs, retained engine XML and counts, slopmark captures, commands and frozen
distribution hashes are below `/private/tmp/erebor-mixed-retry` or named
`/private/tmp/erebor-mixed-*.log` and `*-slopmark-*.txt`. The harness binary SHA-256
is `e3ced16fb594d5162773f617bcda4fe426a4a3020a56da206c15691c6c2d0627`.
The candidate engine JAR SHA-256 is
`dc8a44c3f418e698bc8270ef19d4d0b22037828983898765337010e6e8723496`.
`distribution.json` retains all launcher/library hashes and runtime settings.
`acceptance-checks.json` retains phase reconciliation, checksum, invariant,
cleanup and matched-pair checks. `harness-stock-order.patch` retains the complete
harness commit for independent review or application in another checkout.

## Measured acceptance

The host remains in low power mode. GraalVM 25.0.4, macOS/arm64, `-Xmx1g`,
local durable WAL, loopback TLS and READ COMMITTED with explicit `FOR UPDATE`.
Every run is serial and starts after all compilation/tests end. One passing
sample per build/workload follows the owner's instruction.

The full pair uses **sample all, four workers, one warehouse, seed 42, five
seconds warmup, twenty seconds measured and maximum retries three**, retaining
the 45/43/4/4/4 mix. Only the River executable/version differs between the pair.
The focused precursor selects New Order/Payment with two seconds warmup and
ten seconds measured, otherwise the same configuration.

```sh
RIVER_HARNESS_BINARY=/private/tmp/river-harness-erebor-retry/bin/river-harness \
  /Users/blater/src/ingres/river-harness/benchmark run river tpcc sample all \
  --river-executable=/private/tmp/erebor-mixed-retry/candidate-dist/river \
  --river-version=erebor-three-retry-5cfefeeb-stock-order-356682a-candidate \
  --warmup=5s --duration=20s --workers=4 --warehouses=1 --seed=42 --max-retries=3
```

The control substitutes `/private/tmp/river-ent-candidate/river` and version
`erebor-three-retry-2ada6350-stock-order-356682a-control`. The runner preserves
the exact command arrays in `candidate-summary.json` and `control-summary.json`.

| Run start UTC / BST | Build / workload | Measured commits | Expected rollbacks | Retries | Boundary cancellations | Commits/s | p99 ms | Artifact ID |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 21:57:16 / 22:57:16 | Erebor, focused pair | 5,120 | 22 | 0 | 4 | 511.950 | 30.573 | `river_harness_20260929_215717_6c367071` |
| 21:57:48 / 22:57:48 | Erebor, full mix | 12,064 | 59 | 0 | 3 | 603.141 | 26.722 | `river_harness_20260929_215748_b93ff37d` |
| 21:58:28 / 22:58:28 | Stable `2ada6350`, full mix | 10,872 | 55 | 0 | 3 | 543.536 | 30.458 | `river_harness_20260929_215828_9f1527c2` |

All three report `status: passed`, zero failed/unknown outcomes, successful
post-run invariants, valid report checksums, graceful `river-stop` and removal
of their complete owned instance directories. Warmup also has zero retries,
failures, unknown outcomes or cancellations. Each phase reconciles attempts
to commits, expected rollbacks, retries and terminal cancellations. Full-pair
reports are eligible with the identical key
`58d8890203ee93e6b9614794d6f4bfd7ba66e5c3fe7b109951b1f63823e84fdf`.

Reports are immutable bundles under
`/private/tmp/river-harness-erebor-retry/runs/ARTIFACT_ID`. This is workload
acceptance evidence; these short samples establish no engine speedup or new
performance baseline.

One adjacent MariaDB functional smoke exercises the other full binding:
`sample new-order`, one worker/warehouse, seed 42, warmup 1s, measured 3s and
three retries. `river_harness_20260929_220009_91dd0204` passes all invariants,
commits 2,053 transactions with 23 expected rollbacks, zero retries/failures/
unknown outcomes and one terminal cancellation. Warmup has seven expected
rollbacks and no failures/retries. The run-owned database is dropped and the
owned process stops through `mariadb-admin`; service state remains inactive,
the owned lifecycle directory is removed and all report checksums reconcile.
Read-only checks established inactivity before startup and verified `mariadb
none` afterward. This different workload is a functional smoke, with no
cross-target throughput comparison.

## Decision and review handoff

The strict **three-retry mixed-workload condition is satisfied** with the
corrected, explicitly versioned common binding on both candidate and stable
control. The retry budget, data profile, workers, mix, warmup and measured
duration were retained. Earlier unsuccessful reports remain part of the
investigation history.

The harness fix is committed on its isolated local branch; that repository
has no remote configured. Review its common binding semantics and identity
change at `356682a` together with River's SQL proof at `0b68064f`. Updated
independent Erebor durable-format/recovery/concurrency review remains required
before promotion. No feature merge, checkpoint tag or baseline is designated.
