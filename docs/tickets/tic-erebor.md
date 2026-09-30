---
id: tic-erebor
status: open
type: story
priority: 1
assignee: blater
parent: tic-isildur
delivery: code
tags:
    - performance
    - storage
    - recovery
    - sql
created: 2026-09-29T03:23:25Z
---
# Make the primary index the canonical relational row store

[Tic-thranduil](tic-thranduil.md) measured about 225 `order_line` and 225
`stock` candidates per full Stock Level transaction, matching MariaDB's actual
plan. River performs about 451 separate logical-head lookups and 472 heap
fetches per transaction. After warmup, the profiled run recorded no metadata
directory or indexed-page file reads, so increasing metadata frames is not
the remedy for this Stock Level path. The former scalar base-row B-tree was
already removed; the current logical-head radix traversal and subsequent
version, row-location and heap access remain.

The preferred replacement is a primary B-tree whose leaf entries hold the
canonical row payload. A primary lookup or range scan should obtain a visible
row from the leaf it has already reached. Store non-key columns as a leaf
value, separate from the comparable key and internal separators. Adding
`ol_i_id` or `s_quantity` to the ordering key is not this design.

This is a direction selected for implementation investigation, not a measured
speedup or an approved durable format. Complete the design checkpoint below
before committing to the replacement. Radical internal API and format changes
are allowed; one coherent read/write/recovery implementation must result.

## Scope lock and pickup

- **Observable outcome:** ordinary committed primary reads obtain the visible
  row from the selected primary leaf, removing separate head/location/heap
  access and redundant key rechecks. Demonstrate lower read-path CPU and a
  repeatable end-to-end benefit on unchanged full Stock Level, with no
  unexplained repeated regression in the mutation and mixed controls.
- **Canonical mechanism and owner:** one clustered relational row store,
  implemented by the existing tuple B-tree format/storage owners and engine
  descriptor, mutation, visibility and recovery owners. Page-generation MVCC
  and the existing atomic commit coordinator remain the starting contracts.
- **Maximum change shape:** one vertical replacement across `river-format`,
  `river-storage` and `river-engine`, with necessary internal caller/test
  updates, one layout ADR and evidence. This includes primary row values,
  secondary locators, overflow and any justified identity-to-locator mapping;
  they must serve that one row authority. Adapt its durable records and replay
  through existing WAL machinery; do not add a second commit/recovery path.
- **Non-goals:** SQL/schema/index changes to the workload, tuple-comparator or
  directional-bound optimization, Boromir's frame map, cache-capacity tuning,
  another SQL executor, a new lock manager, WAL force-policy changes, a new
  general value representation, and external harness/comparator development.
- **Stop conditions:** lost updates, incoherent key/row snapshots, broadened
  durability waits, unbounded history, leaked pins, reduced admitted key/row
  capacity, or unexplained repeated workload regression prevent promotion.
  A missing independent correctness prerequisite becomes a dependency before
  implementation; discovery does not silently expand this mechanism.

Ready for pickup at the design checkpoint; there are no recorded prerequisite
tickets. Claim a ticket branch/worktree from the latest pushed stable checkpoint
using the repository workflow. Recheck the source facts below against that
base. Record the design and its independent review in the layout ADR, linked
from this ticket, before building the replacement. Review fixes within this
scope can proceed without another user approval step. Keep this as one
integration/rollback boundary; intermediate branch commits are not partial
production deliveries.

## Evidence and source analysis — 2026-09-29

Production source inspected at `2ada6350`; the measured baseline is the separate
`a8ceade9` source recorded by [Thranduil](../delivery/evidence/2026-09-29-tic-thranduil-indexed-probes.md).

### What differs from MariaDB

The captured MariaDB plan uses a primary range scan for 225 `order_line` rows
and 225 primary point probes for `stock`. InnoDB stores row data with its
primary index, so those accesses reach `ol_i_id` and `s_quantity` at the leaf.
A secondary-index-to-primary lookup is not used by these plan nodes.
[MariaDB's primary/secondary index description](https://mariadb.com/docs/server/ha-and-performance/optimization-and-tuning/optimization-and-indexes/building-the-best-index-for-a-given-select)
explains the distinction. InnoDB still performs visibility checks and can
reconstruct older rows through [undo history](https://mariadb.com/docs/server/server-usage/storage-engines/innodb/innodb-undo-log).

River's measured 450 base-row candidates account for approximately 450 of its
451 head lookups and 472 heap fetches. Retention copied 35.5 kB per transaction;
2,783 page-pin calls include index, row and other work. These are aggregate
counts, not isolated timings. The ordinary River samples were about 1.44–1.46k
TPS; MariaDB's diagnostic sample was 4.78k with different timing windows.
Illustratively, closing that entire gap would require removing about 480 us
per transaction, or 1.1 us per base-row candidate. This arithmetic is a target
cost reduction, not a prediction or valid paired performance claim.

### What the existing implementation makes possible

| Source | Finding and design consequence |
| --- | --- |
| `TupleBTreeLeafEntry`, `TupleBTreePageAppend` | A leaf stores a physical key with logical-row-ID suffix; it has no row value. A pointer-only change still requires an additional data access. |
| `IndexedTupleScanBinding`, `IndexedTupleProbePageProvider`, `TupleBTreeCursor` | Root and page selection already use a visible commit sequence, and the cursor retains its leaf pin. Inline row payload can share that selected page and lifetime. There is no need to reopen the tree for every range candidate. |
| `RelationalDescriptorScanAccess.next`, `RelationalDescriptorPrimaryAccess` | Scans and point reads separately resolve row IDs, fetch rows and re-encode/check their keys. Returning key and row from one coherent leaf entry can remove that second row access and ordinary committed-row recheck. |
| `IndexedPageFrameCache.pinPageAt`, `IndexedPreparedPageBatch` | Pages already have commit visibility intervals and retained predecessor frames. Publication gives readers immutable generations. This is an existing MVCC mechanism, not a proposal to copy MariaDB's undo implementation. |
| `RelationalDescriptorTupleDeltaPreparation`, `RelationalDescriptorTupleDeltaStaging` | An unchanged key currently generates no tuple delta for a non-key update. Clustering must add a row-value replacement even when key bytes are unchanged; unchanged secondary keys still need no payload update. |
| `IndexedHybridMutationCompiler`, `IndexedHybridScalarCompiler`, `IndexedHybridTupleCompiler` | Descriptor rows currently travel through scalar base-row staging separately from tuple changes. Replace that split for clustered rows within the existing atomic commit coordinator and WAL/replay ownership. |
| `IndexedTupleRootSnapshot`, `IndexedTupleRootRegistryWriter` | Root records carry conservative key-membership durability dependencies. A payload update must carry its own observed dependency; a cached unchanged root cannot prove the updated row durable. |
| `TableKeyValidation`, `RelationalDescriptorKeySet` | Tables without a declared primary key are supported. They need the same row store under a hidden logical-ID clustering key, not the superseded relational heap path. |

Page history supports a transaction's earlier view of index membership and
tree structure across concurrent writes and splits. Pins also protect a
reader's bytes across publication. Heap row contents currently have a separate
row-version chain. Clustering offers the opportunity to make one selected leaf
generation provide both key and row, without repeating row-chain resolution.

The history cost needs explicit assessment. A frame reserves 16 KiB; older
generations needed by snapshots or pins cannot be reclaimed. The inspected
cache retains these generations in its frame pool, and prepared-page
reservation can return `RETRY` when no slot is available. A non-key clustered
update would change a primary leaf. The current implementation already stages
heap/head pages, so this does not establish a net increase in copied pages.
Measure both layouts' total changed pages, copied bytes and history occupancy.
Frame size is not a claim that each update adds 16 KiB to WAL.

The earlier insert-focused [tic-4f20](tic-4f20.md) did not justify clustering
from its workload. This ticket uses the newer read-path evidence to reopen
that choice and retains mutation cost as an acceptance condition.

## Candidate decision

| Candidate | Work removed on the ordinary primary read | Remaining cost and decision |
| --- | --- | --- |
| Canonical row payload in primary leaf | Separate logical-head traversal, row-location lookup and heap fetch; potentially row-chain lookup and retention copy as well. | Larger leaves, value replacements and historical leaf retention. **Preferred candidate.** |
| Direct version/physical-row reference in primary leaf | Can remove head/location indirection if reference validity and visibility metadata are sufficient. | Still accesses separate row storage; relocation, updates and old snapshots must keep references valid. Design comparator only; selecting it requires an explicit scope revision before implementation. |
| Duplicated query columns in current index entries | Could avoid a fetch only with a new index-only execution and visibility contract. | Keeps two row representations and does not replace the general access path. Do not add a workload-specific covering index or retain a base-row fetch just to validate the copied values. |

Clustering does not require immediate in-place mutation, a new lock manager or
a second commit path. Keep River's staged atomic publication as the starting
point. Reuse page-generation visibility first; introduce row undo only if a
specific history-capacity or mutation-cost result makes that additional
architecture necessary, then explicitly rescope before implementing it.

## Design checkpoint before implementation

Record one concise layout/ownership decision and have it independently
reviewed. Resolve these concrete questions in that decision; do not leave them
to caller-specific workarounds:

1. **Record layout and capacity.** Define key, stable logical identity, row
   payload, visibility/durability metadata and slot widths. Keep row payload
   out of comparison and separator bytes. Reuse the admitted row representation
   initially unless a concrete size calculation justifies changing it; count
   duplicated key fields in leaf occupancy. Internal pages remain key/child
   pages. Calculate occupancy for the actual harness rows and near-limit keys
   and rows. A row admitted by today's format can nearly fill a page before
   adding a key, so define overflow/continuation ownership without reducing
   the admitted row size to make inline storage fit. Overflow belongs to its
   canonical leaf record and uses generation-safe references and reclamation.
   Resolve whether key columns are reconstructed or duplicated in the value;
   reusing the current row encoding may duplicate them, but creates no second
   row authority. Include secondary key plus primary locator capacity: a
   currently valid pair of large keys must not become inadmissible. Keep
   locator/value bytes out of comparison and internal separators unless they
   are part of the existing ordering contract.
2. **Identity and secondary references.** Prefer secondary entries that carry
   the primary locator and stable logical identity, so a secondary access
   reaches the same row authority directly. Primary-key changes must update
   those locators atomically even when secondary key values do not change.
   Audit `fetchByLogicalRowId`, backfill, FK checks, locks and current-successor
   reads. Carry a locator in existing handles wherever sufficient. If an old
   candidate must follow a row after its primary key moves, decide explicitly
   how stable identity resolves the current locator. Any necessary identity
   map is locator-only with named callers, no row/version authority, and no
   ordinary primary-read access. Count its writes; do not conceal the old
   head path behind a new name. Delete/reinsert must not reuse an old identity.
   A secondary read must resolve its locator at the same selected snapshot;
   following a current locator cannot substitute for the old row after a key
   move. Define hidden clustering-key/root ownership for tables without a
   primary key, including creation, reopen, index backfill and table drop.
3. **Snapshot and durability contract.** Select index and row at one snapshot;
   committed key and value come from the same leaf generation. Preserve
   statement snapshots, SERIALIZABLE protection and own pending mutations.
   Define a durable row modification identity and dependency for payload-only
   updates, candidate/current-row protection, deletions and absence. Preserve
   [tic-e544](tic-e544.md)'s observed-dependency behavior without waiting on an
   unrelated global snapshot sequence. Determine whether root membership
   metadata actually needs to change for a payload-only update; any decision
   to avoid that write must still observe the row's publication correctly.
4. **History and row lifetime.** Specify pin ownership for point results and
   through cursor advance, nested joins, suspension, close, cancellation and
   failure. Borrow selected inline row bytes while their immutable page is
   pinned. Copy only values
   whose consumer lifetime requires it; do not promise zero copies for all
   operators. Demonstrate how an old snapshot and a held cursor constrain
   frame reuse, how pressure is reported, and how progress resumes when they
   release. Do not add an unbounded history or a new arbitrary reader limit.
5. **Atomic mutation and recovery.** Map insert, non-key update, key update,
   delete, rollback and private index build to one primary-row mutation plus
   necessary secondary changes. Specify split/root/overflow publication,
   WAL representation, replay, checkpoint and reclamation. Remove separate
   scalar base-row staging for converted relational rows. Payload-only
   replacements must be represented in pending reads, compilation and replay,
   not inferred from a changed key. Keep catalog/scalar consumers that still
   genuinely need their existing store under their own contract.
   Show how two transactions changing different rows on the same leaf preserve
   both updates, including grouped commit and a split. Preserve existing
   row/key conflict and uniqueness semantics; page replacement must not install
   a stale whole-page image. Define rollback/savepoint cleanup of pending
   values, locator changes and reserved pages.

This checkpoint must include a complete call-path and format sketch, not an
exhaustive database survey. A disposable branch experiment may answer a named
remaining question; it is not an accepted production layout or baseline.
Choose one candidate before building the full delivery. A direct-reference
or row-undo choice needs evidence of the concrete blocker and an explicit
revision of this ticket's scope and acceptance before implementation. If the
clustered mechanism is rejected, retain that decision; do not implement an
alternate mechanism under the current acceptance criteria.

## End-to-end delivery

The intended ordinary read is:

```text
prepare bounds -> seek/advance visible primary leaf
               -> observe dependency -> expose selected row fields
```

Replace the current primary-row indirection completely for descriptor tables,
including tables using a hidden clustering key. Preserve key ordering and SQL
semantics. Migrate point reads, range/full scans, locked reads, DML, secondary
lookup, FK operations, private builds and cleanup to the canonical row store.
No permanent old/new storage mode, dual row authority or migration adapter.
The pre-V1 format can change directly. The layout ADR must identify the format
version/admission boundary and explicit rejection/recreation behavior for old
data and WAL, with a test that rejection occurs before modification. Do not
silently reinterpret old bytes or automatically destroy/recreate a database.

For a committed row returned with its key from one visible leaf entry, enforce
range membership in the B-tree and remove relational key re-encoding/recheck.
This follows from coherent key/value publication. It does not apply blindly
to a pending replacement, a scan whose source snapshot differs, or a row
refetched after locking. State which changed-row cases need a residual check
and consult the direct ordering analysis in [tic-uruk-hai](tic-uruk-hai.md) for
those cases. Preserve necessary residual checks through existing semantics;
implementing that ticket's direct comparator is not required here. Do not
invent per-row proof scans or a second read executor.

The cursor already retains its leaf pin. Its binary initial positioning also
establishes the starting bound: forward traversal need only test the upper
endpoint thereafter, and reverse traversal the lower. That smaller general
optimization can be delivered separately if useful; do not bundle its gain
into the storage-layout attribution. Equal-bound point probes still need
their terminating comparison. Neither this nor a prerequisite cleanup should
delay the clustered-layout decision.

## Measurement and acceptance

- Start from the latest accepted stable checkpoint. Record the actual source;
  do not use the old Thranduil TPS as the new control. Boromir's resident-map
  candidate was rejected, so its presumed benefit is not part of the baseline.
  Preserve reproducible control/candidate commits and executable versions,
  harness revision, JVM/options, host, isolation and durability configuration.
  Capture control counters/profiles and ordinary samples before changing the
  production path; collect the matching candidate evidence after replacement.
- Before broad integration, run the smallest real-path candidate that supports
  unchanged SQL/schema and honest updates/reads. Reject a speed demonstration
  that bypasses visibility, indexes, durability or invariants. Its load, reads,
  updates and recovery must use the candidate authority; a read-only synthetic
  leaf populated beside the old row store is not qualifying evidence. Capture at
  least two identical full Stock Level control and candidate samples,
  interleaved: one warehouse/worker, seed 42, retries 3, warmup 5s, duration 20s,
  using the installed executable through the external harness. Distinguish
  temporary mechanism instrumentation from ordinary throughput runs.
- Collect baseline and candidate mechanism counters and matched production CPU
  profiles in separate diagnostic passes. Report server CPU per committed
  transaction and worker attribution to primary traversal, row resolution,
  retention/decoding and residual rechecks, distinguishing JIT/profiler work.
  Capture virtual-thread execution; do not use the unsuccessful Thranduil
  `ThreadMXBean` stage-timing approach. Fewer calls alone do not prove lower CPU.
- Count primary candidates, separate head/version/location/heap operations,
  leaf/overflow page accesses, cache misses/file bytes, pins, retained bytes
  and decoded columns. Explain changes in row work or file activity.
  For ordinary inline committed primary rows, expect zero separate logical-head,
  row-directory and heap fetches, and no re-encoding merely to prove key
  membership. If page MVCC supplies row visibility, expect no separate
  per-candidate row-version-directory read. Keep catalog/secondary/overflow
  counts separate instead of claiming every transaction-wide count becomes zero.
- Measure leaf occupancy, height, splits, current/historical frames, pinned
  frames, changed pages, staged copies, WAL bytes and checkpoint writes.
  Run matched control/candidate `sample new-order` as the immediate mutation
  control, then `sample order-status` and `sample all` at integration. Use two
  interleaved samples per build for each: one worker for individual families,
  four for the mix, one warehouse, seed 42, retries 3, warmup 5s and duration
  20s, with unchanged isolation, durability and runtime. Add a focused
  long-snapshot plus repeated non-key-update test under the configured frame budget; it must
  show correct old/current values, explicit pressure and recovery after release.
  Widen or lengthen only for a concrete regression or unresolved capacity issue.
- Cover point/range/full scans, both directions, missing and deleted rows,
  own writes and rollback, primary-key changes, secondary/FK references,
  tables without declared primary keys, locked-current successor behavior,
  nested cursor lifetimes and private index builds. Include old-snapshot
  secondary reads across primary-key moves, same-leaf concurrent updates,
  savepoint rollback and hidden-primary lifecycle. The format replacement
  additionally covers oversized rows, splits, page reuse, vacuum, checkpoint,
  WAL replay and crash reopen. Held-force/failure tests must cover payload-only
  updates and negative decisions as well as ordinary row reads.
- Demonstrate no new per-row allocations or per-operation buffer views on
  warmed point/scan paths with focused allocation evidence. Count retained and
  staged copies, name their owners/lifetimes, and remove temporary counters
  unless an existing named consumer requires them. No source-token gates.
- Require a clean full build and independent durable-format/concurrency review
  before promotion, following [AGENTS.md](../../AGENTS.md). Capture slopmark
  before/after as a design review signal. Remove superseded relational head,
  row-directory/version and heap write/recovery paths where no remaining
  consumer needs them; retain shared infrastructure only for named consumers.
- Require passed workload reports, successful invariants and owned-instance
  cleanup, zero failed/unknown outcomes, reconciled retries/cancellations, and
  eligible equal comparison keys within each matched workload/window where
  supplied. Run serially on a quiet host without builds or other workloads.
  Retain anomalous samples and their repeats. If variation obscures the result,
  use a matched longer Stock Level sequence (10s warmup, 60s measurement),
  reversing order to check drift, before deciding.
- Accept only when the intended accesses are removed, read-path CPU improves,
  and ordinary Stock Level runs establish repeatable end-to-end benefit.
  Resolve repeated mutation/mix regressions, history pressure, retry shifts
  and dependency broadening before promotion. If benefit remains inconclusive
  after the targeted longer comparison, or the mechanism is falsified, retain
  evidence and close as `delivery: evidence` without production changes.
  Architectural preference alone does not waive this ticket's performance gate.
  A rejected candidate leaves no production fallback or feature flag. Record
  individual artifacts and the accept/reject decision in
  [performance-checkpoints.md](../performance-checkpoints.md).
  Accepted code also records its pushed integration commit and annotated
  checkpoint tag; designate a new baseline only with the required table row.

Keep workload SQL, user-declared schema/index inventory, isolation, durability
and runtime fixed. Disclose new internal locator structures and their costs.
After the River candidate passes, use identical longer interleaved MariaDB and
River manifests and the independent comparator for any cross-database claim.
That comparison and formal multi-platform qualification are not prerequisites
for this local River delivery.

### Focused validation entry points

Extend the existing format/storage tests first, then the descriptor read/write
and recovery tests. Select only affected classes during iteration, for example:

```sh
./gradlew --no-daemon :river-format:test \
  --tests io.riverdb.format.btree.TupleBTreePageCodecTest
./gradlew --no-daemon :river-storage:test \
  --tests io.riverdb.storage.btree.TupleBTreeTest
./gradlew --no-daemon :river-engine:test \
  --tests io.riverdb.engine.relational.RelationalDescriptorRowPathTest \
  --tests io.riverdb.engine.table.IndexedRelationalWalRecoveryTest
```

These are starting points, not a substitute for the boundary coverage above.
Expand to affected-module tests and policy checks before the clean full
checkpoint. Record independent design and final durable-format/concurrency/
recovery review findings and their resolution; author-only approval is
insufficient. Keep the design ADR, final source, tests and evidence linked from
this ticket so the implementing agent's successor can reproduce the decision.

## Scheduling

User-directed revision, 2026-09-29: this is now the next architectural delivery
after Boromir's rejection. Begin with the design checkpoint, then deliver the
chosen row-store replacement. Uruk-hai is not a prerequisite: reconsider its
cleanup and comparator scope against the selected layout so work destined for
deletion is not optimized first. The tickets retain separate acceptance and
rollback boundaries; this changes queue order, not dependency edges.

Readiness review, 2026-09-29: scope, design handoff, capacity/concurrency
boundaries and measured acceptance are now explicit. The design checkpoint
remains the first task; no layout has been pre-approved by this review and no
dependency edges changed.
