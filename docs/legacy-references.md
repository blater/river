### Explicit exclusions

- QUEL and Embedded QUEL.
- Embedded SQL preprocessors.
- ABF, QBF, VIFRED, forms, and the legacy report writer.
- Ingres Star, Replicator compatibility, ICE, and obsolete protocol bridges.
- Binary compatibility with Ingres data files, WAL, wire protocols, or system catalogs.
- VMS-specific behavior and obsolete operating-system integration.

### Legacy evidence informing River

River treats the current tree as evidence and executable specification rather
than a Java translation target:

- The backend already separates parser (PSF), optimizer (OPF), executor (QEF),
  metadata (RDF), session control (SCF), query cache (QSF), and storage/logging/
  locking (DMF). River retains these responsibility seams.
- Heap, hash, ISAM, and B+tree behavior is visible in
  [`dm1h.c`](../../ingres/src/back/dmf/dmp/dm1h.c),
  [`dm1i.c`](../../ingres/src/back/dmf/dmp/dm1i.c), and
  [`dm1b.c`](../../ingres/src/back/dmf/dmp/dm1b.c).
- Variable 2-64 KB page support is implemented in
  [`dm1c.c`](../../ingres/src/back/dmf/dmp/dm1c.c); River benchmarks a narrower v1
  choice instead of inheriting all sizes automatically.
- Row locking, four isolation levels, MVCC, escalation, and deadlock machinery
  appear in [`dmp.h`](../../ingres/src/back/dmf/hdr/dmp.h) and
  [`lkrqst.c`](../../ingres/src/back/dmf/lk/lkrqst.c). River therefore improves an
  existing concurrency model rather than assuming only page locks exist.
- WAL/LSN, group-commit, CLR, and operation-specific recovery behavior is found
  in [`lgwrite.c`](../../ingres/src/back/dmf/lg/lgwrite.c) and the B+tree split
  recovery in [`dmvesplt.c`](../../ingres/src/back/dmf/dmve/dmvesplt.c).
- [`DatabaseAdmin.pdf`](../../doc/pdf/DatabaseAdmin.pdf) documents heap, hash,
  ISAM, B+tree, locking, MVCC, checkpoint, and recovery behavior.
- The existing [`tests`](../../tests) tree supplies legacy behavioral and stress
  cases to classify rather than blindly require.
- The source distribution contains GPLv2 licensing in
  [`LICENSE.gpl`](../../ingres/src/LICENSE.gpl), while some historical tests carry
  other notices. The provenance ADR precedes any code/test translation.

## 4. System context
