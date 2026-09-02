# River storage formats

The first indexed-table format is deliberately narrow and versioned:

- B+tree leaf, internal, and root-metadata payloads are version 1.
- Local WAL format `1002/1` records compact committed single inserts, bounded
  insert batches, mixed insert/update/delete version batches, and quiescent
  logical vacuum records. Bootstrap and structural leaf splits use atomic
  groups of checksummed 16 KB page images.
- Unknown operation, page, or payload versions fail closed as corruption; no
  implicit upgrade is attempted.
- Recovery currently replays retained WAL from the indexed-table bootstrap
  record. WAL truncation for this format is forbidden until a checkpoint
  records an independently durable page-set base and recovery boundary.

Heap updates are append-only versions; the B+tree points to the newest row and
WAL-reconstructed previous-row links provide snapshot traversal. Deletes are
tombstone versions and a later insert can reuse the key without hiding older
snapshots. When no transaction retains a snapshot, synchronous vacuum keeps
the newest version or tombstone for every indexed key, rebuilds the heap, and
redirects index row IDs as one forced WAL operation. Concurrent horizon-based
pruning, index merge, and page reuse remain later formats.
