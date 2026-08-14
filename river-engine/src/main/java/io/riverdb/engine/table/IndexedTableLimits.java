package io.riverdb.engine.table;

import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.wal.local.LocalWal;

/** Shared fixed bounds for the indexed-table Store/PageSet/Kernel collaboration. */
final class IndexedTableLimits {
  static final int MAX_PAGES = 512;
  static final int MAX_ROWS = CheckpointState.MAXIMUM_ROWS;
  static final int MAX_CHANGED_PAGES = 63;
  static final int MAX_OPERATION_ROWS = LocalWal.MAX_PENDING_RECORDS * 64;

  private IndexedTableLimits() {
  }
}
