# ADR 0009: B-link/B+tree splits and recovery

Status: Accepted

## Context

River needs concurrent point/range access and crash-safe structural changes
without holding a root-to-leaf write latch chain or requiring merge correctness
in the first kernel. Transaction locks protect logical keys; page latches only
protect short physical mutations.

## Decision

Use a B+tree with B-link navigation: every non-rightmost page records an
exclusive high key and a generation-qualified right sibling. A search that
falls at or beyond the high key follows right links until the key is covered,
then validates page generation and search state. Parent separators are routing
hints; a temporarily missing parent separator cannot make the new right page
unreachable.

A split is a redo-only structural system operation with a stable operation ID.
Its exact foreground order is:

1. Before any tree-page latch, reserve a new page identity/generation, both
   buffer frames, the bounded split-record range, and any parent-work ticket.
2. Descend without retaining a parent latch. Acquire the target left page
   exclusively, validate its generation, level, high-key range, right link, and
   `PageWalToken`; on mismatch release and restart.
3. Hold the new right page exclusively while it is still unreachable. Copy the
   upper key/child range into it; do not remove those entries from the left.
   Initialize its level, generation, old high key, old right link, split
   operation ID, and checksummed header.
4. Publish one versioned physiological split WAL record before making the
   split visible. It contains the complete prior/new headers, moved key/child
   range, both page identities/generations, separator, old/new links/high keys,
   expected prior tokens, and the new common token. Publication cannot wait
   because capacity was reserved; force is not awaited under a latch.
5. Apply that record in memory copy-before-remove: finalize the right page,
   install the left page's new right link and exclusive high key, then remove
   the copied upper range from the left. Assign the same split
   `PageWalToken`, checksum state, and dirty epochs to both pages. No reader can
   observe an intermediate state because both exclusive latches remain held.
6. Release the unreachable/new right latch, then the left latch. The release of
   the left latch publishes a reachable right page and the left fence together
   to subsequent traversals. Enqueue parent repair only after this publication.
7. Re-descend to the parent without child latches, take the parent exclusively,
   and validate child generation/split operation. Publish and apply an
   idempotent separator-insert record. Parent overflow recursively follows the
   same protocol; a duplicate matching separator is completion, not corruption.

If the old root splits, initialize a new root privately with the two child
identities/generations. Publish its root-install WAL record, then atomically
publish `(rootPageId, rootGeneration, rootInstallToken)` to new traversals.
Durable root selection is recovered from that record and the redundant control
generation; an unlogged or partially initialized root is never selectable.
The old root remains a valid navigation start until the new root publication
is visible and retained readers leave it.

Recovery dispatches records to K08-owned handlers through K11 in WAL order.
For a split record it validates lineages and expected generations, initializes
or verifies the right page first, copies/verifies its full moved range and old
fence/link, then installs the left fence/link and removes only the logged moved
range. It assigns the common token last. Parent and root records are replayed
after their child split record. A fully matching operation ID/token is a no-op;
a strict logged prefix is completed; any unlogged conflicting generation,
separator, link, key range, or root publication is corruption. Writeback of
every resulting page still waits for its same-lineage `DurableWalEnd`.

Phase 1 K08 stores one current index version per key/row mapping. Full indexed
MVCC, uniqueness under concurrency, range/gap locking, and cleanup arrive in
T04 after the transaction visibility protocol is proved. Delete removes or
marks entries without merging pages. Page merge, redistribution, and root
contraction are deferred until a separate protocol and model/crash proof exist.

## Invariants

- Searches never move left and never follow an unvalidated generation.
- The new right page receives a complete copy before the left page changes its
  fence or removes any key; publication never creates a missing key interval.
- A matching duplicate interval during redo/parent lag is resolved by high-key
  routing and operation identity, never returned twice to an index scan.
- No thread waits for a logical lock, WAL force, I/O, or capacity while holding
  a page latch.
- User rollback never undoes a completed structural system transaction.
- A missing parent separator is repairable/replayable; an unreachable committed
  key range is not allowed.
- Logical delete/tombstone in Phase 1 removes no tree page. Page merge,
  redistribution, root contraction, unlink, and physical page removal are
  deferred; if later introduced, removed pages enter pending reuse until WAL,
  cursors, snapshots, and writeback cannot observe the old generation.

## Consequences

Splits and root growth can be made restart-safe before implementing the harder
space-reclamation path. Phase 1 index behavior is intentionally narrower than
the Phase 2 product contract, without pretending to satisfy indexed MVCC early.

## Alternatives

- Holding an exclusive root-to-leaf latch chain was rejected for contention.
- Including concurrent page merge in K08 was rejected because it expands the
  navigation, recovery, and ABA proof on the G1 critical path.
- Treating a structural split as user-transaction undo was rejected because a
  concurrent reader may already depend on the new routing state.

## Required evidence

- P08 model tests interleaving traversal, split, parent completion, root growth,
  cancellation, and restart.
- Crash tests at every new-page, split-record, link/high-key, parent, root,
  force, and writeback boundary.
- Heap/index agreement, duplicate/missing-key, range-order, and generation-reuse
  tests before G1.

## Authoritative context

- [High-level storage plan](../plans/river-high-level-plan.md)
- [Implementation plan K06-K11](../plans/river-project-implementation-plan.md)
- [TigerBeetle comparison](../plans/river-tigerbeetle-comparison-and-recommendations.md)
