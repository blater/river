# Erebor implementation progress review

Date: 2026-09-29. Reviewed base: `2ada6350`; implementation HEAD:
`53fde91a`, including the uncommitted read/overflow changes present during
review in `/private/tmp/river-erebor`. The implementing agent was still editing;
locations below identify the inspected methods, not a frozen final candidate.

## Direction and disposition

**Continue with the clustered-row design, but fix I1–I3 before treating the
current read/write path as correct.** I4 is an important performance/ownership
correction. This is a progress review, not final format/recovery approval or
evidence of a speedup.

The implementation has made useful changes at the intended owners:

- Tuple leaf values and modification sequences flow through the existing tuple
  intent and logical WAL machinery. No second commit coordinator was added.
- Primary scans can bind directly to the selected immutable leaf value, and
  skip ordinary committed-row key re-encoding.
- Secondary locator replacement is staged when the primary key moves, even
  when the secondary ordering key stays unchanged.
- The root record now persists membership separately from structural generation;
  tuple reads observe membership and returned-entry modification sequences.
- Splitting includes value bytes and can split an existing leaf, then retry
  insertion to produce additional siblings. Inline eligibility is independent
  of fullness. The scalar-operation pin path now uses configured changed-page
  capacity rather than the former 63-page limit.

## I1 — P1: Key move back followed by value update becomes a delete

**Location:** `IndexedTupleIntentLog.effectiveOperation`, lines 159–171.

For one existing physical key, the raw history `DELETE, INSERT, REPLACE` should
have the final effect `REPLACE`. The implementation remembers the first raw
operation and the latest raw operation. When the first is `TUPLE_DELETE`, it
handles latest `TUPLE_INSERT` specially but returns `TUPLE_DELETE` for latest
`TUPLE_REPLACE`.

A concrete relational sequence is: committed row at key A; move A to B; move
B back to A; update a non-key column. A's journal entries are delete, insert,
replace, so the active final A intent is a delete. B's insert/delete cancels.
Commit therefore removes A's primary entry even though the transaction's final
row exists. The still-present scalar row path can conceal this divergence.
Savepoint rebuilding calls the same faulty function.

**Fix:** derive the net operation from initial existence and final existence,
preserving the latest value whenever the row exists. A first delete followed
by a latest replace must retain a replace. Keep append and truncate/rebuild
under one semantic owner.

**Proof:** one journal regression plus a relational A-to-B-to-A/non-key-update
case, asserting pending reads, committed primary/secondary reads, savepoint
restoration and reopen. Existing key-change-back tests use valueless intents
and do not exercise this new value-bearing sequence.

## I2 — P1: Foreground and replay disagree on membership for replacements

**Locations:** `IndexedHybridTupleCompiler.compileLoaded`, lines 50–52;
`IndexedPublishingTupleCompiler.compileLoaded`, lines 54–56;
`IndexedTupleRootRegistryWriter.resultingMembershipSequence`, lines 46–58.

Foreground compilation passes `deltas.count(...) > 0` as `membershipChanged`.
`IndexedTupleDeltaCompiler.count` includes `TUPLE_REPLACE`. Thus every ordinary
non-key update advances the primary membership sequence, and an unchanged-key
secondary locator replacement advances secondary membership too.

Replay advances membership only for insert/delete or registry lifecycle change.
A replace-only commit therefore produces different root membership metadata
in foreground execution and replay. In the live database, tuple scan admission
also observes the needlessly advanced membership sequence, so unrelated durable
rows wait for the payload update's force. This defeats the explicit R3 contract.

**Fix:** use one membership-change policy in compilation and replay, separating
insert/delete/lifecycle changes from value/locator replacement. Do not fix this
by making replay advance on every replacement. Also remove unconditional
root-registry staging for a replacement that changes neither membership nor
the root; otherwise it still incurs avoidable scalar heap/version/WAL work.
Adapt the suboperation evidence and sizing through their existing owners.

**Proof:** held-force reads of changed and unrelated rows, both with and without
a structural split; compare foreground and replay membership metadata. Include
an unchanged secondary ordering key whose locator changes.

## I3 — P1: Open scans can return rows superseded by their own later writes

**Locations:** `IndexedTupleScanMerge.prepare/next`, lines 19–59;
`RelationalDescriptorScanAccess.next/bindPrimaryLeaf`.

The merge captures pending ordinals only at open. It neither checks journal
generation nor refreshes a captured ordinal to its current active successor.
Primary scans now bind the returned committed/pending value directly and skip
the old scalar fetch that checked the latest pending mutation on every candidate.

Two concrete cases need preserving:

1. Open a primary scan with no pending mutations, then update/delete an
   unconsumed row in the same transaction. The merge still returns its committed
   bytes. A primary-key move outside the bounds can also return the old row
   without a residual check, because the source is labelled committed.
2. Open after a pending replacement, then replace/delete that row again. The
   saved ordinal is inactive but still contains the old operation/value;
   `next` does not check `activeAt`, so it can return the superseded value.

The existing write APIs permit mutations with active scans. Before this change,
`IndexedTransactionReadAccess.fetchByKey` gave latest own writes precedence;
the committed database snapshot alone does not preserve that behavior.

**Fix:** keep latest-own-write resolution in the canonical tuple cursor/intent
owner. Reconcile journal changes at cursor boundaries, or resolve a candidate's
latest intent with bounded indexed lookup. Define how ordering/key moves and
savepoint rollback affect the open cursor. Preserve the fast committed path
when the journal is unchanged; do not restore a committed-row heap fetch or
add a full journal scan for every result.

**Proof:** open-then-update/delete/key-move for an unconsumed row, both with and
without an intent present at open; include rollback to a savepoint while the
cursor is retained. Check forward/reverse behavior through the real descriptor
read API and any affected SQL path.

## I4 — P2: Point and secondary-locator fetches still copy full rows

**Locations:** `RelationalDescriptorPrimaryAccess.fetchEncoded` and
`StoredTableRowView.bindCopied` (introduced in the uncommitted read changes).

The helper copies every committed row into `ownedBytes`, then closes its tuple
cursor before returning. Pending rows first copy into `pendingRow` and then
copy again into `ownedBytes`. Passing a column selection to binding does not
avoid the full-byte copy loop. Allocation grows to each newly encountered
larger row length. Secondary locator fetches now share this helper as well.

The copy is currently necessary for the lifetime created by closing the cursor,
but that lifetime choice conflicts with the planned pinned point-result owner.
This should not become the final synchronous point-read contract. It does not
establish a measured regression, and the ordinary primary range path already
avoids this copy.

**Fix:** give the result/role explicit ownership of its leaf/overflow borrow
until reset, reuse, close or cancellation, including nested roles. Copy selected
values only when the actual consumer must outlive that borrow. Consolidate the
retention owner rather than adding another general row container next to
`HeapRowResult`. Remove the second pending-value copy where no separate lifetime
requires it.

**Proof:** retained/nested point-result lifetime and cancellation/pin release,
then warmed allocation and copied-byte evidence for direct point and secondary
lookup paths as well as the Stock Level range/probe path.

## Remaining delivery boundaries visible in this partial implementation

These are incomplete parts of the agreed replacement, not reasons to select a
different architecture or create parallel tickets:

- Descriptor insert/update/delete still stage scalar base rows as well as tuple
  values. Full scans and locked-current resolution still have scalar consumers.
  Hidden-primary creation and the declared-primary identity-to-locator map
  must complete before deleting that authority. Finish the end-to-end replacement
  before acceptance measurements; current dual writes distort write cost and
  can hide disagreement between authorities in tests.
- Overflow allocation/read support exists, but the inspected replace/delete path
  has no old-reference retirement. `IndexedTupleGraphReclaimer.owned` recognizes
  tuple B-tree pages only, so table/index cleanup also omits overflow pages.
  Add snapshot-safe retirement, checkpoint/replay allocation state and drop
  cleanup before repeated large-row updates are accepted. Keep the old-leaf,
  not-yet-pinned-overflow case from R4.
- Early incompatible-format rejection still needs its real open-path proof;
  bumping leaf/root/WAL decoders alone does not establish rejection before all
  file creation/truncation/recovery writes.
- Prove greater-than-63 changed-page admission through an actual logical
  mutation, same-leaf grouped updates, split/replay equivalence, and history
  pressure recovery. Lower-level format tests alone do not prove these paths.

## Recommended immediate sequence

Fix I1 and I2 first, then establish I3's open-cursor contract while finishing
the read owners. Finish point-result pin ownership and overflow reclamation
alongside that migration. Complete hidden-primary/identity/current-row callers
and remove scalar descriptor staging. Only then run the candidate acceptance
measurements against the retained controls.

This review inspected source and test changes. It did not run Gradle, tests or
benchmarks in the actively edited worktree, and it makes no claim that the full
106-file change has passed a final independent review. Production files were
left to the implementing agent.
