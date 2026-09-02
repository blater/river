package io.riverdb.engine.table;

import io.riverdb.wal.local.LocalWal;

/** Shared fixed bounds for the indexed-table Store/PageSet/Kernel collaboration. */
final class IndexedTableLimits {
  /** Positive int page ids remain the transitional on-disk page reference domain. */
  static final int MAX_PAGES = Integer.MAX_VALUE - 1;
  /** Maximum positive logical row id encoded as an unsigned 32-bit value. */
  static final long MAX_ROWS = 0xFFFF_FFFEL;
  /** Page-image WAL is bounded to 1 MiB and cannot encode a 64th changed page. */
  static final int MAX_CHANGED_PAGES = 63;
  /** Logical WAL can reconstruct pages and may use every staging frame except one. */
  static final int MAX_LOGICAL_CHANGED_PAGES = 127;
  static final int MAX_OPERATION_ROWS = LocalWal.MAX_PENDING_RECORDS * 64;

  private IndexedTableLimits() {
  }
}
