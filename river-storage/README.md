# River storage formats

The first indexed-table format is deliberately narrow and versioned:

- B+tree leaf, internal, and root-metadata payloads are version 1.
- Local WAL format `1002/1` records compact committed inserts. Bootstrap and
  structural leaf splits use atomic groups of checksummed 16 KiB page images.
- Unknown operation, page, or payload versions fail closed as corruption; no
  implicit upgrade is attempted.
- Recovery currently replays retained WAL from the indexed-table bootstrap
  record. WAL truncation for this format is forbidden until a checkpoint
  records an independently durable page-set base and recovery boundary.

Phase 1 entries are single-version. MVCC index entries, deletion, merge, and
page reuse are later transaction-stage formats and must use new versions.
