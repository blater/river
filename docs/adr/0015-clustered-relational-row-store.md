# ADR 0015: Clustered relational row store

Status: Accepted and promoted at `fdfda831` (2026-09-29 UTC / 2026-09-30 BST), checkpoint `perf-checkpoint-20260929-tic-erebor-clustered-row-store`; [approval and validation](../delivery/evidence/2026-09-29-tic-erebor-promotion.md)

## Context and decision

`tic-erebor` replaces the descriptor-table scalar base row with one canonical
row value in its primary tuple B-tree. A committed primary point lookup or
range scan selects a leaf generation at the statement snapshot and obtains
the row from that entry. This removes the separate logical-head, version,
row-location and heap access for an inline row. The key is compared for tree
ordering; the value is never part of a separator or comparison. Keep the
current stored-row encoding initially, including copies of key columns in the
value. Those bytes are one row authority, not another index entry.

The engine uses the existing atomic commit coordinator, logical WAL group,
page-generation publication, transaction locks and snapshot rules. Catalog
and other actual scalar consumers retain their store. Descriptor rows do not.
No read-only shadow index, dual-write migration mode or compatibility adapter
is admitted.

## Format and capacity

Use a new tuple-page format with a 72-byte header and 40-byte leaf slots.
Each slot contains key offset/length (two 32-bit fields), value offset/length
(two 32-bit fields), overflow page ID (32 bits), overflow generation (64 bits),
last row or locator modification commit sequence (64 bits), and flags (32
bits). The physical primary key keeps the existing comparable user tuple plus
stable logical-row-ID suffix. Internal slots remain key/child entries. Primary
leaf values are encoded descriptor rows; secondary leaf values are a primary
physical-key locator. The secondary physical key retains its current
logical-row-ID suffix. Thus its stable identity is available even when its
locator changes. A secondary locator is versioned with its leaf generation.

An inline value occupies the same leaf as its key. The tuple-storage owner
chooses placement from encoded lengths, independent of current leaf occupancy
or cache pressure. With payload capacity `P = 16,256`, header `H = 72`, leaf
slot `S = 40` and maximum physical fence key `F = 3,080`, an entry with physical
key length `K` and value length `V` is inline exactly when:

```text
S + K + V <= P - H - F = 13,104 bytes
```

This guarantees that an inline entry fits a leaf with any admitted high fence.
It is a placement boundary, not a lower row-admission limit. Ordinary fullness
causes byte-aware splitting. Account for values, slots and the actual high
fence of each output leaf. Retain a valid two-leaf split where possible; if no
such boundary exists, partition into enough ordered sibling leaves and insert
their separators through the existing staged tree mutation. A large middle
entry may require more than two output leaves. Do not spill inline-eligible
rows or reject an admitted row merely to preserve the old binary-split API.
Choose split boundaries deterministically from the ordered bytes, with a fixed
tie-break; use the same planner during compilation and replay.

Only an entry exceeding the inline boundary stores a generation-qualified
reference to an immutable overflow page. Its 40-byte payload header holds
magic/version, owner logical row ID, row length, removal commit sequence and
retirement-queue successor; the page
envelope owns its primary key ID and generation. The largest currently admitted
encoded row is 16,216 bytes: 16,256 payload bytes minus the 40-byte overflow
header leaves 16,216 bytes. One overflow page holds any currently admitted row.
An overflow leaf entry retains its key, slot, value length and reference, with
no inline value bytes. A maximum secondary key plus primary locator uses
`S + K + V = 40 + 3,080 + 3,080 = 6,200` bytes and remains inline; including
the header and a maximum fence uses 9,352 payload bytes. Locators stay outside
comparison and separator bytes.

Re-evaluate placement only when key/value bytes change. Growth within the
inline boundary splits if needed; crossing it allocates overflow. Shrinking
below it returns the value inline and retires the old overflow reference.
Changing an overflow value allocates a new immutable page and retires the old
one. Moving an unchanged entry during a split preserves its placement,
overflow reference and modification sequence. All transitions are in the same
atomic mutation and use the retirement rules below.

For the full harness schema, the `order_line` row has a 44-byte fixed prefix
and at most 32 text bytes; its four-part physical key is 51 bytes. At the
maximum row length, a 40-byte slot plus key and value uses 167 bytes and a
leaf has a nominal packed capacity of 96 entries before fence/split effects.
`stock` has a
111-byte fixed prefix and at most 384 text bytes; its two-part physical key
is 31 bytes. The corresponding 566-byte entry gives a nominal packed capacity
of 28 entries. Both are inline under the rule above. These are packing
calculations, not minimum realized occupancy; implementation must measure the
actual populated leaves, heights, split counts and inline/overflow counts.

Tables without a declared primary key receive a persisted, invisible primary
tuple descriptor at creation. Its comparable one-part BIGINT user key equals
the allocated stable logical row ID; the physical suffix retains that same
identity. The key, root and owner are created, reopened, backfilled and dropped
with the table. It is never exposed as a user-declared key. A table always has
exactly one primary row authority.

## Identity and reads

A versioned table-local identity-to-primary-locator tree serves callers that
need to resolve an identity whose declared primary key can change:
`fetchByLogicalRowId` and locked current-successor resolution. Audit backfill
and foreign-key callers individually: an operation already scanning primary
rows or holding a usable locator consumes that row/locator directly. The map stores only
the current primary physical key for each identity, never row bytes or a row
version chain. Primary point and range reads never visit it. Insert writes
the mapping; primary-key change replaces its locator; delete removes it.
The map uses the same page-generation snapshot and commit group as the
primary leaf. For a hidden logical-ID primary key, derive the physical locator
from the stable identity; no identity-map tree is needed. Deleted identity
values are not reused.
This adds one locator-tree mutation on insert, primary-key change and delete,
and none on a non-key update. A primary-key change also replaces the locator
value in every surviving secondary entry; an unchanged secondary ordering key
does not make that value replacement optional.

A secondary entry carries the primary physical key selected at its own
snapshot. A secondary read follows that locator in the primary tree at the
same snapshot and checks the stable ID. A primary-key move updates every
secondary locator atomically even when the secondary ordering key is
unchanged. Following the present-day identity map for an old secondary
candidate is invalid; the old snapshot must use the old secondary locator.
After an exclusive logical-row lock, a current-row operation may instead
resolve the newest locator from the identity map and refetch the protected
row. Own pending insert, replacement, key move and delete take precedence
over committed pages through the session's pending-intent overlay.

The call path is: encode bounds, select tuple root at the statement snapshot,
seek/pin a leaf, read the matching key and value from one leaf generation,
observe that entry's modification sequence, then decode only selected fields.
An overflow value adds one generation-checked page pin. Ordinary committed
primary rows need no relational key re-encoding or bounds recheck. Pending
replacements, refetches after locking, and rows selected under a different
snapshot retain the residual bound checks required by their source.

### Identity-index access and maintenance budget

An internal identity descriptor has two roles depending on the table. With no
declared primary key, its tree is the canonical clustered row store; there is
no second identity-to-locator tree. With a declared primary key, its tree holds
only stable row ID to primary-locator mappings. Keep this distinction explicit
in the owning APIs and in measurements.

| Operation | Required route | Separate identity-map work |
| --- | --- | --- |
| Primary point/range read | Selected clustered leaf supplies the row. | Zero lookups. |
| Full table scan or primary-row backfill | Scan the canonical clustered tree directly. | Zero lookups. |
| Secondary read | Follow the entry's primary locator at the same snapshot. | Zero lookups. |
| Identity-only read on a declared-primary table | Resolve identity to locator, then read the primary tree. | One mapping resolution when the caller has no usable locator. |
| Identity-only read on a hidden-primary table | Derive its clustering key from the logical row ID. | No separate map exists. |
| Locked/current-row access | After the existing protection, use a supplied locator if it still names the required current row; resolve stable identity when the locator is absent or no longer valid. | Conditional; do not route every update through the map. |
| Non-key update on a declared-primary table | Replace the primary row value; preserve the unchanged identity mapping. | Zero mapping mutations. |
| Insert/delete/primary-key move on a declared-primary table | Maintain the mapping atomically with the primary and affected secondary entries. | One logical mapping insert/delete/replacement, with physical page and WAL costs counted. |

Checking a protected locator must preserve stable row identity, current-version
and own-pending-write semantics. It must not accept an old-snapshot row as the
current successor. Keep this decision in the existing row-access/locking owner;
callers carry a usable locator rather than discarding it and recovering it by
another tree search. Foreign-key and backfill callers that already hold the
needed row or locator consume it directly. The map must not become a universal
row-fetch adapter or a second row/version authority.

Deriving the internal key ID from a reserved range of durable table IDs happens
at descriptor construction/reopen. This does not require a per-row lookup or
allocate a new durable identity on each open. The owning catalog admission must
keep the ranges disjoint and handle exhaustion explicitly. Cheap descriptor
identity derivation does not make the populated locator tree free: its entries,
splits, WAL, checkpoint writes and retained page generations consume resources.

Acceptance evidence must report identity-map lookups and mutations by operation
and workload separately from primary/secondary work. Prove zero map accesses
on ordinary primary and secondary reads, and zero map mutations on non-key
updates. For locked updates, distinguish a supplied usable locator from an
identity-only or moved-key successor request. Measure the actual extra changed
pages, copied bytes, WAL bytes, history occupancy and CPU; logical mutation
counts alone are insufficient. Use the ticket's New Order control and an
affected Payment control when its current-row path exercises this map. A read
gain does not excuse an unexplained repeated write-workload regression.

## Visibility, lifetime and pressure

The selected leaf generation makes key and value coherent. Keep three distinct
identities under their existing owners:

| Identity | Persisted owner | Changes when |
| --- | --- | --- |
| Structural root/page generation | Tuple root registry and page envelope | A root/page image is replaced; select it at the statement snapshot. |
| Membership commit sequence | New 64-bit field in the tuple root registry record | Committed insertion, deletion or ordering-key move changes that tree's membership; initialize at index creation/publication. |
| Entry modification commit sequence | Leaf slot | That row or locator changes; stamp the assigned member sequence. |

Append the membership sequence to the root record, bump its version and encoded
size, and propagate it through root encode/decode, caching, compilation,
lifecycle and replay. A payload-only replacement changes its entry sequence.
If it splits the tree or replaces the root, publish the structural root change
with the membership sequence carried forward unchanged. If neither topology
nor membership changes, no root-registry write is needed. Unrelated entries
keep their modification sequences across page rewrites and splits.

The tuple read owner observes the stored membership sequence where it currently
observes the root registry row's MVCC sequence. Loading structural root bytes
must not itself add that newer registry-row sequence to the session dependency.
Preserve the existing conservative per-index membership dependency, including
missing keys and exhausted ranges; do not substitute the global snapshot or
page generation. Observe each examined row's entry sequence before any
value-dependent predicate can discard it, including aggregate and locked-read
paths. Thus a filtered-out updated row still contributes to the result's
durability dependency, while an unrelated row's payload update does not.

Apply the same rule to secondary trees and identity maps. A secondary locator
replacement with unchanged ordering key advances its entry sequence without
advancing membership; observe that locator and the selected primary row.
An identity-map lookup observes its mapping entry when found and its tree's
membership sequence for absence. Preserve catalog/lifecycle dependencies for
creating, publishing and dropping indexes through their existing owners.
SERIALIZABLE key/range protection and logical-row
write locks continue to own conflicts; leaf publication alone is not a lock.
An insert or key move acquires the new-key uniqueness/range protection, and a
key move retains protection for the old key and stable logical row through
the decision. Current-row mutation refetches after its logical-row lock and
checks the candidate still names that row; a stale candidate returns the
existing retry/conflict status before staging any page.

Point results borrow a pinned immutable leaf until their result/view is reset
or the next point operation. Scan results borrow the cursor's current leaf
until advance, close or cancellation; an overflow result additionally pins
the named overflow generation. Nested roles have independent cursors and pins.
An operator that keeps a value across advance or suspension copies only the
needed bytes into its bounded owner. All failure and rollback exits release
pins. A snapshot or held cursor retains required old leaf and overflow
generations. The existing configured frame budget returns `RETRY` for
reclaimable pressure and `RESOURCE_EXHAUSTED` when one operation cannot be
admitted; progress resumes after pins/snapshots release and reclaim runs.
No unbounded version chain or arbitrary reader count is added. The page
allocation/reclamation owner records overflow retirement as page ID,
generation and the commit sequence removing its current reference. That
retirement is logical WAL evidence and checkpointed allocation state. Reuse
requires that no retained snapshot or pinned leaf can still reach the retired
reference, no overflow pin remains, and the retiring WAL/checkpoint coverage
permits reuse. An old leaf may reference an overflow page it has not pinned
yet; neither zero overflow pins nor the allocation sequence proves reuse safe.
Pure leaf movement never retires the referenced page. The leaf's page ID plus
generation fences stale reuse. Retained retirement state obeys the configured
page budget; pressure returns an explicit status before the commit decision.

The implemented overflow header records the removing commit sequence while
the old generation retains its row bytes. An overflow insert, replacement or
delete may reclaim one older retired page during commit preflight. Selection
requires a checkpointed retirement marker, a visible-snapshot floor at or
after its removing sequence, and no pinned pre-retirement tuple leaf or
overflow frame. The logical WAL suboperation names the exact reclaimed page,
durable generation and retirement sequence; replay applies that free-stack
transition before the tuple mutations and registry publication. The free-stack head
and page identity are staged with the mutation, so a later grouped member can
allocate the page with a higher durable generation. A release alone does not
free a page: checkpoint and a subsequent overflow mutation drive progress.

## Mutation, WAL and recovery

The descriptor mutation plan emits one primary row put/delete per row, plus
secondary locator and identity-map changes. Non-key update emits a primary
value replacement even when key bytes are equal. Primary-key update deletes
the old primary entry, inserts the new entry with the same logical ID,
replaces the identity mapping and replaces all secondary locators. Insert,
delete, private index build and backfill use that same authority. Savepoint
rollback truncates pending row values, locator changes and reservations to
its recorded mutation boundary; full rollback cancels all staged pages.

Extend the existing logical relational mutation stream with primary put/delete,
secondary/identity locator operations and their exact key/value bytes. Include
expected/resulting entry and membership sequences, structural root identities,
and overflow allocation/retirement identities. The same deterministic storage
applier reconstructs the affected pages in foreground compilation and replay;
record and check allocation/root outcomes where replay requires exact identity.
Do not add page images alongside these logical records. `IndexedPageBatchCodec`'s
63-page limit has no engine caller in the reviewed base and does not limit this
path.

`IndexedRelationalWalPlan` owns bounded logical-record chunking. Admit the exact
stream bytes and all chunks before the decision; the current maximum row/key
payload must remain representable. Admit aggregate staged pages separately
against the configured page budget. A primary-key change touching many
secondary locators can span chunks and more than 63 changed pages. Return a
resource status before the decision when its admitted budget is insufficient.
Incomplete groups never publish and are not replayed as committed mutations.

Commit-group preflight applies members serially to cumulative
staged pages, checking each row/key predecessor and uniqueness before freezing
each member. Two transactions changing distinct rows on one leaf therefore
apply to successive staged generations; the second never publishes an old
whole-page copy. The assigned member commit sequence stamps the affected
entries in foreground compilation and replay. Splits and root changes are
part of the same staged page set and logical WAL evidence.

Preserve the existing shared commit pipeline:

1. Preflight, reserve and freeze every member's cumulative page generation.
2. Append the complete logical WAL group and its irrevocable decision.
3. Install its immutable generations and publish visibility through the
   existing coordinator/frontier boundary. No reader sees a partial mutation.
4. Keep durability ownership/pins while WAL force is pending, preventing
   premature data-file writes or reuse. Independent durable reads can complete;
   actual observed dependencies and commit acknowledgments wait for force.
5. On force completion, advance durability and release eligible ownership;
   on failure, preserve existing fencing and dependent-result failure behavior.

The direct-commit path retains its existing force-before-publication ordering.
Clustered storage adds no new force barrier, commit path or acknowledgment rule.
Replay reconstructs primary, secondary, identity and overflow pages through
the same mutation applier and checks expected/resulting identities. Checkpoint
includes their current pages and free-page state; overflow reclamation runs only after
the snapshot, pin and durable-coverage conditions above.

Remove descriptor base-row scalar intents, relational logical-head and
version/row-directory/heap writes, and their descriptor replay cases in this
same delivery. Retain kernel facilities still used by catalog/scalar rows.

The format replacement bumps the tuple page, root registry, relational WAL and
checkpoint format versions and adds the overflow payload kind and hidden-primary catalog
metadata. Existing on-disk databases and WAL are rejected at open before
recovery, checkpoint, file creation/truncation or replay can change their
bytes. The user must explicitly recreate an old pre-V1 database; startup
never does so automatically. A reopen test must compare file bytes before and
after rejection.

## Retirement allocation metadata

The allocation root stores a durable FIFO head, tail and count. Detached
single-page overflow values carry its intrusive successor. Retirement appends
the page atomically with removal of its leaf reference. Before applying an
allocating descriptor, compilation pops up to its admitted overflow allocation
demand from the global queue, regardless of the retired page's original owner.
Selection touches the queue head and allocation metadata; it never scans
unrelated database pages. Drop cleanup unlinks queued references before freeing
or re-identifying those pages.

Each reclaimed page is an explicit logical mutation with page ID, successor,
original key ID, durable generation and removal sequence. Replay verifies the
exact head and page identity before publishing the same free-stack transition.
The records share the existing bounded mutation arena and WAL chunk stream.
Logical-output admission reserves one possible reclamation per overflow
allocation; unused reservations produce no WAL records.

A head is eligible only after its removal marker is checkpointed, the snapshot
floor permits reuse and old leaf/overflow pins have ended. Staging a queue link
does not make an already checkpointed removal ineligible. A newly staged or
prepared removal remains ineligible. When the head blocks, allocation consumes
available free/fresh pages; exhausting the structural page-ID domain returns
`RESOURCE_EXHAUSTED` without publication. Releasing the retaining reader/pin
and checkpointing permits retry of the same atomic transaction.

## Review and promotion

The independent reviewer must examine capacity arithmetic, same-leaf member
ordering, page/overflow reclamation, snapshot-safe secondary key moves,
negative-read dependency and old-format rejection. Record findings and fixes
here before production implementation. A final independent durable-format,
concurrency and recovery review is also required before promotion. The
performance and correctness gate remains [tic-erebor](../tickets/tic-erebor.md).

### Independent review — 2026-09-29

The [independent design review](../delivery/evidence/2026-09-29-tic-erebor-design-review.md)
supports the clustered-row direction. The corrected proposal above addresses
its four findings:

- **R1:** shared publication remains before force, with retained durability
  ownership and dependency-gated results; direct ordering remains unchanged.
- **R2:** logical WAL chunking and staged-page admission have separate owners;
  the unused page-image codec imposes no new limit or logging requirement.
- **R3:** persisted membership sequence is separate from structural root and
  per-entry modification sequences, including predicate rejection and absence.
- **R4:** page-derived placement is independent of fullness; byte-aware splits
  preserve inline-eligible values, including large middle entries requiring
  additional sibling leaves.

Capacity wording, overflow retirement and identity-only consumer routing are
also corrected. The [focused follow-up](../delivery/evidence/2026-09-29-tic-erebor-design-review.md#focused-follow-up--2026-09-29)
accepted the corrected design for implementation and identified the active
tuple-root 63-page admission path as a specific code/test obligation. Final
independent code/recovery/concurrency review and the performance gate remain
required before promotion.
