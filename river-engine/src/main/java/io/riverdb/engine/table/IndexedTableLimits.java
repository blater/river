package io.riverdb.engine.table;

import io.riverdb.storage.btree.BTreeStructuralLimits;

/** Shared fixed bounds for the indexed-table Store/PageSet/Kernel collaboration. */
final class IndexedTableLimits {
  /** Durable page references and B-tree traversal share one structural page-id domain. */
  static final int MAX_PAGES = BTreeStructuralLimits.MAXIMUM_PAGE_ID;
  /** Maximum positive logical row id encoded as an unsigned 32-bit value. */
  static final long MAX_ROWS = 0xFFFF_FFFEL;
  /** Page-image WAL is bounded to 1 MiB and cannot encode a 64th changed page. */
  static final int MAX_CHANGED_PAGES = 63;
  private IndexedTableLimits() {
  }
}
