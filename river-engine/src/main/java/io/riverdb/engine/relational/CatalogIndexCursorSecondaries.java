package io.riverdb.engine.relational;

import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Reusable actual-count duplicate tracking for one catalog-index scan. */
final class CatalogIndexCursorSecondaries {
  private final ColumnBitSet observed = new ColumnBitSet();
  private int expected;
  private int count;

  StatusCode prepare(int ready, int total) {
    if (ready < 0 || ready > SqlShapeLimits.MAX_SECONDARY_INDEXES
        || total < ready || total > SqlShapeLimits.MAX_SECONDARY_INDEXES) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = observed.reserve(total, SqlShapeLimits.MAX_SECONDARY_INDEXES);
    if (status.isOk()) status = observed.clearForSize(total);
    if (status.isOk()) expected = ready;
    if (status.isOk()) count = 0;
    return status;
  }

  boolean record(int slot) {
    if (slot < 0 || slot >= observed.bitCount() || observed.get(slot)) return false;
    observed.set(slot);
    count++;
    return true;
  }

  boolean complete() { return count == expected; }

  void reset() {
    observed.reset();
    expected = 0;
    count = 0;
  }
}
