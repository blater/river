# Erebor updated review handoff

Date: 2026-09-29 UTC. Branch: `feature/tic-erebor-clustered-row-store`.
Base: stable `origin/master` at `2ada6350`. Final production/test source:
`46e5ab39c35004759f617d29961d777c7791636e`. This remains an unpromoted
replacement; intermediate commits are not separate releases.

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

The control major is 4, allocation-root version 5, overflow version 2 and
relational logical-WAL version 9. [ADR 0015](../../adr/0015-clustered-relational-row-store.md)
describes the current layout, ownership, retirement and pressure contracts.

## Validation and measurements

The final serial clean `clean check :river-server-app:jar` passed in 4m 30s:
156 actionable tasks, 102 executed, 52 restored from cache and two up to date.
All 1,131 engine tests passed, with no errors or skips. Log:
`/private/tmp/erebor-promotion-final-clean-check.log`.

The [write-cost evidence](2026-09-29-tic-erebor-write-cost.md) contains actual
map-owned page/copy/WAL/history/flush measurements, additional tuple-key
protection counts, virtual-thread JFR attribution and the named workload
controls. It retains the earlier slow samples that prompted the catalogue
correction. The host is in low power mode, and the owner permits one good
sample per build/workload. The accepted
[Stock Level decision](2026-09-29-tic-erebor-candidate-stock-level.md) retains
its original source versions and variation; it has not been rerun or relabelled.

## Promotion conditions

Updated independent durable-format, recovery and concurrency review remains
required by [AGENTS.md](../../../AGENTS.md). Review the new durable queue and
exact cross-owner replay identities, checkpoint eligibility for staged links,
old leaf/overflow pins, blocked-head pressure, group force dependencies,
cancelled descriptor numbering/floors, and the migrated multi-chunk coverage.

The strict four-worker `sample all`, retry-limit-three run exhausted deadlock
retries on both stable control and candidate, including repeated controls.
Individual families passed. Short New Order results were below the control;
the targeted longer reversed-order pair passed at 373.911 candidate versus
278.695 control commits/s, with zero retries and failures. A matched ten-retry
mixed diagnostic also passed (353.348 versus 348.186 commits/s), with explicit
retry accounting and cleanup. It does not waive the strict three-retry mix.
The evidence retains all slow/failed runs and the CPU/JIT limits. Review/owner
resolution of that retry condition is required before promotion.

No merge, performance checkpoint tag or baseline designation is approved by
this handoff. The feature branch is retained for review.
