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

Updated independent durable-format/recovery/concurrency review and final clean
integration validation remain required. Write-cost evidence is recorded
separately; the owner's accepted Stock Level decision remains unchanged.
