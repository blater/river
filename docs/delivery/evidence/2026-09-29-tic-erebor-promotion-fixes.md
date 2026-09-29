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

## Earlier validation

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

## Amended review F1: retirement metadata ownership

The independent follow-up reviewed `867f6847` and is retained at
`/private/tmp/erebor-promotion-review-2/review.md`. Commit `fb9772d2` addresses
its P1 finding. Queue append and drop unlinking now hold the root's operation
pin throughout link acquisition and publication, and release it on success,
corruption and resource pressure. The two free-stack release callers,
`IndexedRetiredOverflowReclaimer` and `IndexedTupleGraphReclaimer`, also pin
metadata through free-page staging. The tuple provider releases an existing
allocation-root writable borrow before the queue takes ownership. The ordinary
read routes and identity-map maintenance policy retain their existing owners.

The operation allocator's production callers in tuple/overflow allocation,
logical scalar splits and logical-head allocation already retain metadata
pins through free-head and new-frame acquisition. Its ownership contract is
now explicit, and retirement fixtures use the same pinned allocation contract.
The reusable pin carriers add no allocation inside these queue operations.
No format version or minimum resident-frame count changed.

Four new regression tests exercise four current frames, **two staging frames**
and fifteen active staged slots:

- Append spills unrelated staged data while preserving root count/tail and the
  overflow successor; abort restores both, and repeating the operation works.
- A held unrelated staging pin forces `RESOURCE_EXHAUSTED`. Append publishes
  no queue change; releasing the pin and aborting permits the identical retry.
- Drop unlink/free-stack work succeeds after spilling, rejects insufficient
  residency, and abort restores queue links, free state and original ownership.
- Cross-owner reclamation and exact recovery replay retain the FIFO/free-stack
  state after repeated staging spill. Abort restores the original queue.

The new append/drop regressions failed before the ownership correction. Their
retained result is `/private/tmp/erebor-followup/pressure-before.xml`.
The actual relational reuse/recovery test now runs both its ordinary profile
and a production-compiled four-staging-frame profile. It captures a closed,
checkpointed database before the narrow-cache transaction, then copies only
the new WAL decision. Reopen reconstructs both overflow rows and identity
locators, and a checkpoint shows no remaining retired pages. This avoids
copying transient sparse staging data into the crash fixture.

## Amended review F2: repeatable steady-state allocation checks

Commit `4e08034d` addresses the P2 finding without a byte allowance. Temporary
JFR probes on GraalVM 25.0.4 reproduced the exact **21,752-byte** point-batch
spike twice. With TLAB disabled for object attribution, allocation events show
class-loader byte arrays, class objects, strings and resource-loading work.
Class-load events in that batch name `StoredTableRowIntegerFilter` and
`StoredTableColumnSelection`, reached from the primary row-binding path even
though the measured point operation supplies null for both parameters. This
is one-time type resolution; the trace does not establish a sustained
per-row allocation or attribute every warmup byte to compilation.

The test explicitly initializes those two types during setup. It warms the
same allocation-counter/read helper used for verification until ten
consecutive 10,000-point batches allocate zero bytes, with a maximum of 100
warmup batches. Failure to stabilize fails the test. Scan warmup also measures
its helper and requires at least ten consecutive zero-byte batches at the end
of its existing 100-scan warmup. Each path then must pass **five separate
exact-zero verification batches**. Verification does not retry an allocating
batch or select a minimum. Primary and secondary scans still assert that the
row view borrows its selected primary leaf buffer.

In the initialized control probe, batches 4–39 were all zero and the two late
parameter-class loads were absent from the read window. Other setup types
loaded in batch zero. Raw recordings, temporary probe source, per-batch bytes
and class-load events are retained in `/private/tmp/erebor-followup/`:

- `allocation-probe.jfr`, `allocation-probe-deep.jfr` and their logs retain the
  two original spike reproductions.
- `allocation-probe-initialized.jfr` and its log retain the initialized control.
- `allocation-summary.json` contains the paired batch values and loaded types.
- `allocation-repeat-1.xml` through `allocation-repeat-3.xml` retain three
  passing fresh-JVM runs of the final uninstrumented test. Every point and
  primary/secondary scan verification batch measured zero bytes.

The probes are outside the worktree and production distribution. TLAB/JFR
options applied only to the temporary investigation, not the accepted test.

## Latest integration checkpoint

Final production/test source is `4e08034d`, containing F1 at `fb9772d2`.
The source changes were committed without alteration after the serial clean check passed.
All 52 affected tests passed with zero failures, errors or skips, covering the
queue, cache, cancelled commits, overflow churn, logical WAL commit/recovery
and actual read allocation. Logs and XML: `affected-passed.log` and
`affected-results/` below `/private/tmp/erebor-followup/`.

The final command used cached Gradle 9.7.0, the existing separate worktree
caches, `--no-daemon --max-workers=1 clean check :river-server-app:jar`.
It passed in **4m 51s**, with 156 actionable tasks: 102 executed, 52 from cache
and two up to date. All **1,136 engine tests** passed, with zero failures,
errors or skips. The log is `/private/tmp/erebor-followup/clean-check.log`;
counts and focused final XML are `final-engine-counts.json` and `final-results/`
in the same directory. `git diff --check` passed.

The investigation also retains two fixture-only interruptions/failures:
`focused.log` was stopped while a whole-file crash copy expanded sparse
staging space; its owned temporary directory was removed, and the WAL-only
fixture replaced that copy. `affected-final.log` records an optional direct
publication attempt exceeding the four-current-frame retention budget; that
extra fixture was removed because the real WAL-only recovery test supplies
the durable boundary. The final affected and clean runs above include neither
fixture problem.

Slopmark's compact table-package capture has incomplete SHALLOW coverage:

| Owner | Before | After |
| --- | ---: | ---: |
| Retirement queue | 26.027 | 28.707 |
| Retired overflow reclaimer | 28.512 | 29.543 |
| Tuple graph reclaimer | 25.492 | 31.871 |
| Tuple page provider | 92.712 | 93.119 |
| Operation allocator | 17.978 | 17.978 |
| Frame cache | 264.687 | 264.687 |

The graph-reclaimer increase prompted a responsibility review: it now retains
its existing allocation metadata safely; queue selection, eligibility,
resource policy and publication remain in their existing owners. No second
allocator, queue or commit path was introduced. Raw captures are
`/private/tmp/erebor-followup-slopmark-before-all.txt` and
`/private/tmp/erebor-followup/slopmark-after.txt`.

Decision: both amended findings are ready for independent follow-up review.
No new workload campaign or throughput claim is made. The prior physical-cost
and Stock Level evidence keep their original source versions and scope. The
strict three-retry mixed-workload condition and independent durable/recovery/
concurrency approval remain unresolved; no promotion is recorded.
