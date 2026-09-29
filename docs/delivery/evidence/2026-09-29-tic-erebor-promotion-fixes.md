# Erebor promotion-review fixes

Review source: `/private/tmp/erebor-promotion-review/review.md`, 2026-09-29,
reviewed feature commit `bdf43fc0` against stable `2ada6350`.
Branch: `feature/tic-erebor-clustered-row-store`.

## R1: cancelled tuple work

Commit `33442464` skips inactive descriptors consistently in logical sizing,
WAL sizing, descriptor emission and DML/lifecycle compilation. Logical-row-ID
floors remain in the commit. Focused tests passed for completely cancelled
work, cancelled work with another table's surviving row, savepoint rollback,
and floor/row preservation across reopen. `IndexedRelationalWalCommitTest`
also passed.

## R2 and R3: demand-sized reclamation and bounded selection

Commit `803011be` implements the replacement described below.

The allocation root now owns a durable head/tail/count FIFO of retired overflow
pages. Overflow pages contain the intrusive successor. Compilation consumes
up to the admitted overflow allocation demand and records each exact page,
original owner, durable generation, successor and retirement sequence as a
logical mutation. The existing mutation arena, resource accounting and WAL
chunk stream own the bounded records. Replay performs the same exact queue
and free-stack transitions before tuple writes. Other tables' eligible pages
can supply the allocation demand.

Only queue candidates are read; unrelated pages are never traversed during
ordinary allocation. Snapshot, old-leaf and overflow-pin eligibility remains.
A checkpointed removal stays eligible when only its queue link is staged;
newly retired generations do not. Drop cleanup unlinks candidates before reuse.
The control major version is 4, allocation root version 5, overflow payload
version 2 and relational logical WAL version 9. The 40-byte overflow header
still admits the full 16,216-byte encoded row limit.

Focused validation passed:

- Eight two-row overflow update/checkpoint rounds retain exactly two live and
  two retired overflow pages and a constant file size after the first update.
- Cold databases with 40 and 400 unrelated scalar pages and a four-frame cache
  read only the root metadata once, then zero pages on the next empty-queue
  attempt. The review measured 37 reads on each attempt with 40 pages.
- Two retired pages with different owners supply two allocations at structural
  page-ID exhaustion, with increasing generations; abort restores their queue,
  original owners and allocation state. An older snapshot floor prevents reuse.
- Successive prepared members reclaim distinct checkpointed heads, and drop
  unlinking rolls back with the surrounding staged operation.
- One real descriptor commit reuses two pages from another table and survives
  WAL-only replay with current rows and stable identity mappings intact.
- Existing descriptor overflow/snapshot/capacity tests, grouped commit/held-force
  tests, logical WAL codec tests, format codec tests and tuple storage tests pass.

## R4: superseded descriptor BASE path

Commit `960c25a7` removes the superseded path and migrates recovery coverage.

Removed the descriptor BASE opcodes, `appendBase` API, negative descriptor -1
admission, decoder branch and BASE applier. The applier now owns only the
separate SCALAR path. Existing kernel/catalog scalar machinery remains.

Continuation, truncation, digest, provider-batch and multi-chunk fixtures now
carry clustered tuple values. The wide-record test round-trips a canonical
primary value, 64 secondary primary locators and the identity locator. An
actual descriptor transaction commits 80 overflow rows with a secondary
index; their row values alone exceed a physical WAL record. WAL-only reopen
preserves all primary values, stable identities, secondary locators and
13,600-byte overflow values. Scalar/tuple atomic publication, lifecycle,
vacuum and drop/reuse tests retain their separately owned SCALAR coverage.
Focused codec, recovery, commit and overflow-churn classes pass.

## Final validation

The uninstrumented candidate at `0b2e7bbb` passed a serial clean full checkpoint
in 4m 57s. Subsequent JFR measurement found excluded logical-head searches in
catalogue scans. `46e5ab39` corrects the scalar/head interval intersection in
the existing scanner; empty/populated boundary tests and the descriptor class
passed. The [write-cost evidence](2026-09-29-tic-erebor-write-cost.md) retains
the diagnosis and before/after measurements.

The final `clean check :river-server-app:jar` at `46e5ab39` passed in 4m 30s:
156 actionable tasks, 102 executed, 52 restored from cache and two up to date.
All 1,131 engine tests passed with zero errors or skips. The final log is
`/private/tmp/erebor-promotion-final-clean-check.log`; counts are in
`/private/tmp/erebor-write-cost/final-engine-test-counts.json`. The earlier
checkpoint log and module counts remain retained separately.

The new warmed real-path read test measures zero allocated bytes for 10,000
primary fetches and each 64-row primary/secondary scan, with the row view
borrowing the selected primary leaf's actual buffer. Its XML is retained at
`/private/tmp/erebor-write-cost/final-clustered-read-results.xml`.

Updated independent durable-format/recovery/concurrency review remains
required. [Write-cost evidence](2026-09-29-tic-erebor-write-cost.md) is recorded
separately; the owner's accepted Stock Level decision remains unchanged.
