# Logical-row head lookup decision

Date: 2026-09-28. Status: design decision for `tic-isildur`; implementation and
performance acceptance remain open.

## Evidence and scope

The unchanged `full stock-level` path obtains a table-local logical row ID from
a tuple index and calls `fetchByKey(baseRows(tableId), logicalRowId)`. That call
searches the shared scalar B-tree, resolves the physical version chain, then
uses the physical-row directory to locate the heap record. Pending writes are
resolved by the transaction session before this committed fetch.

The existing sampled join report
`/private/tmp/river-stock-latest-timing.txt` recorded 3,451,275 base fetches
over 15,339 Stock Level calls (about 225 per call). In 53,926 sampled fetches,
the scalar lookup took 19,551,341 ns, or about 0.363 microseconds per sampled
fetch. The separately sampled aggregate execution was about 633 microseconds
per call. Multiplying the two observations suggests that even eliminating the
entire lookup without replacement could save only about 82 microseconds per
call in that instrumented run. This is a rough ceiling, not a predicted gain:
samples include warmup, the timings are nested, and any replacement has cost.
The 593.840 TPS diagnostic run is excluded because other high CPU processes
were active, as the user identified.

The scalar B-tree stores 24-byte entries, at most 256 per 16 KiB page. A
database with at least 400,000 stock and order-line base rows needs at least
1,563 leaf pages, so a lookup can traverse an internal root, another internal
page and a leaf, as well as reading the scalar-root metadata page. This is a
source-derived lower bound on page count, not a measured cache-miss count.
The existing physical-row directory stores 8-byte records, but its key is a
physical version ID; it cannot answer a `(tableId, logicalRowId)` lookup.
With the candidate's 40-byte directory-page header, a 16 KiB River page can
hold 2,027 eight-byte heads or 4,054 four-byte child pointers. The two
full-profile tables above would occupy about 198 head pages plus sparse radix
branches and shared table-map pages while their IDs remain below roughly
8.2 million. This is a format-capacity
model, not a measured disk footprint or cache hit rate.

## First candidate

Use a paged, directly addressed logical-row head directory. Each entry maps
`(table object ID, table-local logical row ID)` to the latest physical version
ID. Keep the existing heap and version directory. Read the latest head from
this directory, then use the existing version chain to select the snapshot's
visible row. A zero head means no committed row for that logical ID.

This is the first implementation candidate because it changes fewer existing
ownership and recovery contracts. It removes the
per-row scalar search and its 24-byte leaf entry while preserving the current
heap layout and version chain. A clustered primary store would also remove the
search, but moving wide rows into primary-key leaves changes their fanout,
split behavior, key-update work, secondary-index references, and overflow
handling. The available evidence does not establish that either replacement
has higher throughput. Promotion depends on measured read, write, WAL and
recovery outcomes.

| Concern | Current scalar heads | Direct head directory | Clustered primary rows |
| --- | --- | --- | --- |
| Indexed base read | Root metadata and B-tree path, then version/heap access | Cached table-map leaf, radix leaf, then existing version/heap access | Primary leaf can carry row and version data; secondary probes still resolve through primary |
| Base update | Heap version plus scalar leaf rewrite | Heap version plus one head-leaf rewrite | Primary leaf rewrite; possible split or overflow work for wide rows |
| Mapping capacity | 24 bytes per leaf entry, 256 entries/page | 8 bytes per head, 2,027 entries/page | Depends on row width; no fixed fanout estimate without a row layout |
| Recovery | Logical relational WAL replays scalar mutations; page-image WAL covers separate indexed operations | Replay relational base-space `SCALAR_*` and explicit `BASE_*` records into head pages and validate resulting roots and page allocation | Replace primary row, version, secondary-reference and vacuum contracts |

The read timing is measured; the other cells describe work required by the
respective layouts. Candidate write latency, WAL volume and recovery cost have
not yet been measured. If those measurements contradict this selection, revise
the decision before accepting production code.

The directory belongs in the existing indexed page set. Normal relational
commits record base-space `SCALAR_*` WAL suboperations; explicit `BASE_*`
suboperations also exist for replay and tests. Commit preflight builds and
freezes candidate page generations, and recovery recompiles
those records into pages. Stage head pages with the existing prepared batch,
and make replay rebuild the same head pages. Direct commits force their WAL
decision before installing pages. Shared groups append the decision, install
the prepared pages and publish the commit under the transaction manager's
snapshot barrier, then force and acknowledge durability. Preserve that order,
its retained durability pins, and fencing on force failure. Install the head
pages before the commit frontier becomes visible. The fixed table map stores
the current row-tree root in page generations under the same WAL decision;
a bounded cache stores only the table-map leaf page ID and rereads its current
root entry on each lookup. Failed staging therefore cannot publish a cached
root. Logical WAL's expected/resulting page allocation and row-count evidence
must cover new directory pages and reject divergent replay. The separate
page-image WAL path remains for its existing indexed operations and must also
recognize the new payload kind when it restores such pages. Checkpoints must
include directory pages and the durable per-table root mapping.

Page 4 is a fixed, typed table-map root in the indexed page set. Its sparse
two-level radix path maps every valid table object ID to a table-leaf entry.
Each entry stores the table's row-tree root page ID and height. This avoids a
versioned scalar root record, another heap version, and a catalog keyspace
reservation. The current root may serve an older snapshot because growth
retains the prior subtree and row-version chains select visibility. New map
and row-tree pages are allocated under the existing watermark and become
durable with the same logical decision as their base mutation. Recovery
reconstructs the same map before serving reads. Reclamation of a retired
table's tree requires an oldest-snapshot boundary and a separate lifecycle
decision; the current candidate retains those pages.

Allocate head pages under the existing page watermark. Cache up to 256
table-map leaf page IDs; read each root entry from its current page generation.
Address table-local IDs through
sparse radix pages so a new table or a high reserved ID
does not allocate its entire possible range. Grow the radix height without
changing existing entry locations; the root publication must be in the same
logical commit as the first entry requiring the new height. A lookup needs no
scalar search once the table root is loaded.
Admit the required pages against the existing page-ID ceiling and prepared
frame/member budgets before appending WAL. Return `RETRY` when splitting a
shared cohort can satisfy the budget; return `RESOURCE_EXHAUSTED` before the
durable decision when one transaction or the page-ID space cannot fit. A high
reserved logical ID allocates only its radix path and head leaf, never the
intervening pages.

For a base insert, compare the prior head with zero; for update or delete,
compare it with the mutation's expected previous physical ID. Stage the heap
version and directory head in one prepared batch. Publish both under the
existing direct or shared commit ordering above. Recovery replays the logical
decision through the
same mutation compiler; checkpoint and vacuum must preserve
the head of every live logical row and the chain needed by older readers.
Current and older snapshots may read the latest head, provided the version
chain is retained by the oldest-active-snapshot and vacuum policy and remains
traversable for that snapshot. Pages are pinned only while accessed. The
transaction layer continues to resolve pending insert, update and delete
before committed access.

Replace relational `BASE_*` scalar writes and reads together. Other scalar
keyspaces, including catalog and tuple-root records, keep the scalar B-tree.
Base table scans must iterate live logical IDs or an existing tuple index;
they cannot retain the old base-row scalar scan as a fallback. Remove base-row
entries from validation, vacuum and recovery expectations in the same delivery.
Enumerate populated radix leaves in logical-ID order and scan their nonzero
entries; do not loop across unallocated IDs, since aborted reservations can
create large gaps and reservations can commit out of order. Keep the existing
base-space lock identity and transactional pending-row
merge for full scans, including SERIALIZABLE phantom protection. A deleted
head remains a version-chain head until vacuum can prove that no reader needs
the prior version; reclaiming a leaf must not remove another live head. The
vacuum stream walks scalar leaves before directory row leaves, then rewrites
both kinds of head leaf. Its stream order is internal to the replayable vacuum
operation, so it need not be global `(space, key)` order. The scanner, head
validation and publisher must include directory heads without retaining
base-row scalar entries.

## Acceptance measurements

Before promotion, instrument a control and the exact candidate build to count
scalar base-tree searches, directory page visits, copied row bytes, decoded
columns, logical WAL bytes, prepared page generations and pages dirtied per
base mutation. Compare
interleaved `full stock-level` and `sample new-order` runs with identical SQL,
schema, indexes, seed, isolation, durability and runtime. Report every sample
and reject runs affected by unrelated host CPU load. The expected structural
result is zero scalar base-tree searches for relational base reads and writes;
the throughput result remains unknown until measured.

Focused tests must cover current and older snapshots, READ COMMITTED refresh,
pending mutations, rollback, key changes, delete and reinsert, sparse IDs,
radix growth, page pressure, cancellation, checkpoint, replay, crash recovery
and vacuum. Independent durable-format and concurrency review, a clean full
test build and the performance checkpoint rules remain required before merge.
