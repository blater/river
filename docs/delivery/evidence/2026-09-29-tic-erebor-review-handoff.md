# Erebor accepted review handoff

Date: 2026-09-29 UTC. Branch: `feature/tic-erebor-clustered-row-store`.
Base: stable `origin/master` at `2ada6350`. Final production source:
`4e08034d` (F1 at `fb9772d2`); additional SQL concurrency proof at `0b68064f`.
The corrected harness binding is `356682a`, locally integrated at `9c0be772`.
The owner confirmed independent review passes and approved promotion of
feature `04cd0917`. The accepted merge is `fdfda831`, checkpoint
`perf-checkpoint-20260929-tic-erebor-clustered-row-store`; the
[promotion record](2026-09-29-tic-erebor-promotion.md) owns final validation
and publication. Intermediate commits were not separate releases.

## Review findings addressed

The independent promotion review at `bdf43fc0` is retained in
`/private/tmp/erebor-promotion-review/review.md`. The
[review-fix evidence](2026-09-29-tic-erebor-promotion-fixes.md) records the
implementation and focused validation:

- R1, `33442464`: cancelled tuple descriptors are omitted consistently from
  sizing and compilation. Stable identity floors and unrelated surviving
  work survive commit, savepoint rollback and reopen.
- R2/R3, `803011be`: the allocation root owns a durable intrusive retirement
  FIFO. Reclamation supplies the admitted overflow demand, includes other
  tables' pages and emits exact queue/page/generation/removal identities in
  the existing logical WAL. Empty-queue selection reads only root metadata;
  it does not scan unrelated pages. Repeated two-row churn stays at two live
  and two retired pages after each checkpoint. Capacity, abort, grouped
  publication, drop unlinking, pinned/snapshot eligibility and WAL-only reuse
  are covered. A blocked FIFO head stops selection; exhaustion reports
  `RESOURCE_EXHAUSTED` without publication, and release/checkpoint permits
  retry of the identical atomic operation.
- R4, `960c25a7`: descriptor BASE APIs, opcodes, admission, decoder and apply
  branches are deleted. The separate SCALAR owner remains for kernel/catalog
  consumers. Recovery fixtures now contain clustered values, secondary
  primary locators, stable-identity locators and overflow. A real 80-row
  multi-chunk commit survives WAL-only reopen.
- `0b2e7bbb`: warmed actual primary points and primary/secondary scans borrow
  the selected primary leaf buffer with zero measured row allocation.
- `46e5ab39`: measured catalogue scans no longer search an excluded, empty
  logical-head directory. Exact scalar/head boundary tests cover empty and
  populated directories and the first included head row.

The amended independent review at `867f6847` is retained in
`/private/tmp/erebor-promotion-review-2/review.md`. Its findings are addressed:

- F1, `fb9772d2`: queue append/unlink and reclamation/free-stack publication
  retain metadata pins through page acquisition. Two-staging-frame regressions
  cover spill, pressure, abort/retry and exact replay. The real descriptor
  reuse test also recovers from a checkpoint plus only the new WAL decision
  with four staging frames.
- F2, `4e08034d`: controlled JFR reproduced the exact 21,752-byte spike as late
  parameter-type loading. Setup now initializes those types, the measured
  helpers must reach consecutive zero-byte warmup batches, and five separate
  verification batches per path must each allocate exactly zero. Leaf-buffer
  identity assertions remain. Three fresh-JVM test runs passed.

The [amended fix evidence](2026-09-29-tic-erebor-promotion-fixes.md#amended-review-f1-retirement-metadata-ownership)
contains the allocation investigation, pressure fixtures and retained limits.

The control major is 4, allocation-root version 5, overflow version 2 and
relational logical-WAL version 9. [ADR 0015](../../adr/0015-clustered-relational-row-store.md)
describes the current layout, ownership, retirement and pressure contracts.

## Validation and measurements

The fresh integration `clean check :river-server-app:jar` on merge `fdfda831`
passed in 5m 35s: 156 actionable tasks, 125 executed and 31 up to date.
All 1,137 engine tests passed with no failures, errors or skips; repository
counts are 2,109 passed, zero failed/errored and 19 existing platform/opt-in
skips. Log: `/private/tmp/erebor-promotion-final/clean-check-fdfda831-cached.log`.
The earlier production checkpoint remains retained in
`/private/tmp/erebor-followup/clean-check.log`. Promotion changes documentation
only; the tested merge tree exactly matches the reviewed feature tip.

The subsequent [three-retry resolution](2026-09-29-tic-erebor-three-retry-mix.md)
adds a real READ COMMITTED opposing-order cycle and consistent-order control.
The affected engine check passes all 1,137 tests. Harness tests, race checks,
vet, the focused New Order/Payment run, the full three-retry candidate/control
pair and an adjacent MariaDB functional smoke pass. The shared full binding
orders stock access while preserving generated line identity and quantities.
Its version/digest records that policy change; River production is unchanged.

The [write-cost evidence](2026-09-29-tic-erebor-write-cost.md) contains actual
map-owned page/copy/WAL/history/flush measurements, additional tuple-key
protection counts, virtual-thread JFR attribution and the named workload
controls. It retains the earlier slow samples that prompted the catalogue
correction. The host is in low power mode, and the owner permits one good
sample per build/workload. The accepted
[Stock Level decision](2026-09-29-tic-erebor-candidate-stock-level.md) retains
its original source versions and variation; it has not been rerun or relabelled.

## Independent acceptance and promotion

The [retained follow-up review](2026-09-29-tic-erebor-followup-review.md)
accepts the metadata pin lifetimes, spill/pressure and recovery outcomes,
and exact-zero allocation stabilization. All 53 independent tests pass;
the earlier durable-format, recovery and concurrency review is included.
The copied report retains its original source/date and then-pending workload
condition. The [three-retry resolution](2026-09-29-tic-erebor-three-retry-mix.md)
subsequently satisfies that condition through the versioned common binding:
candidate and stable control both report zero retries/failures/unknown outcomes,
passed invariants, equal comparison keys and complete owned cleanup.
The owner's latest message confirms independent review passes and Erebor is
ready for promotion, with no further code changes requested.

The earlier failed three-retry runs, ten-retry diagnostic, short New Order
decline and longer reversed-order control remain in the write-cost evidence;
the accepted Stock Level decision retains its source and host variation.
The completed promotion records the merge and annotated checkpoint without
designating a new baseline or making a cross-database speedup claim.
