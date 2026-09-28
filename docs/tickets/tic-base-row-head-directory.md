---
id: tic-base-row-head-directory
status: closed
type: performance
priority: 1
assignee: blater
parent: tic-isildur
delivery: code
tags:
    - performance
    - storage
    - recovery
created: 2026-09-28T20:55:00Z
---
# Remove the additional base-row B-tree search

A tuple-index scan yields a logical row ID. The former relational base-row
path searched a separate scalar B-tree to obtain the version head, then read
version metadata and located the heap row. Repeated stock probes paid for
this additional search after the SQL index scan.
InnoDB instead stores table data with its clustered primary index, so its
primary-key search reaches the row ([MariaDB documentation](https://mariadb.com/docs/server/architecture/server-constraints/primary-key-constraints)).

## Delivery

- Use one sparse, directly addressed logical-head directory for relational
  base-row version heads. Keep other scalar keyspaces on their existing tree.
- Remove relational base-row entries from the superseded scalar tree and from
  its write, scan, vacuum and recovery paths. Do not retain a fallback.
- Preserve snapshot visibility, pending writes, lock protection and failure
  cleanup. Cover high logical IDs, directory growth, vacuum, checkpoint, WAL
  replay, crash reopen and force failure.
- Compare the unchanged full Stock Level and sample New Order workloads against
  the accepted control with matching schema, index inventory, seed, isolation,
  durability and runtime. Rerun anomalously low samples and record all results.

## Acceptance

Source inspection must show that relational base-row lookup reaches
`IndexedLogicalHeadDirectory` without `BTreePage.lookupLeaf`. Focused recovery
and transaction tests plus a clean full build must pass. A selected full Stock
Level run and its adjacent control/candidate samples must be recorded with
their limitations; New Order must show no repeated unexplained regression.
The [performance checkpoint](../performance-checkpoints.md#2026-09-28--combined-indexed-read-candidate-checkpoint)
holds the run artifacts and build evidence.

## Completion

`IndexedKernelVisibility.lookup` now dispatches relational base-row spaces to
`IndexedLogicalHeadDirectory.lookup`; its scalar B-tree path serves other
keyspaces only. The directory and its write, vacuum and recovery ownership
were implemented in `1146864f`, with the combined selected-read candidate at
`699e3c9b`. The high-ID, older-snapshot, vacuum, checkpoint, WAL replay,
crash-reopen and force-failure tests passed. Independent ownership and recovery
review found no blocker. `./gradlew --no-daemon clean check` passed on the
combined source with 156 tasks.

The accepted 60-second full Stock Level run recorded 1,390.067 TPS, with a
second candidate at 1,345.899 TPS versus adjacent controls at 1,265.998 and
1,219.711 TPS. The same-workload New Order check found no repeated candidate
regression after anomalously low results were rerun. The accepted result is a
local measured checkpoint, not a cross-database claim. The parent epic remains
open for full-workload copied-byte and decoded-column evidence.
