package io.riverdb.engine.table;

import io.riverdb.wal.local.LocalWal;

/** Shared fixed bounds for the indexed-table Store/PageSet/Kernel collaboration. */
final class IndexedTableLimits {
  /** Positive int page ids remain the transitional on-disk page reference domain. */
  static final int MAX_PAGES = Integer.MAX_VALUE - 1;
  /**
   * Row ids remain positive int values in this first runtime slice. Metadata is paged lazily, so
   * constructing a table no longer allocates arrays for this theoretical upper bound.
   */
  static final int MAX_ROWS = Integer.MAX_VALUE - 1;
  static final int MAX_CHANGED_PAGES = 63;
  static final int MAX_OPERATION_ROWS = LocalWal.MAX_PENDING_RECORDS * 64;

  private IndexedTableLimits() {
  }
}
