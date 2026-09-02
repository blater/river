package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Geometrically retained projected-column ordinals for one scan cursor. */
final class SqlScanProjectionColumns {
  private int[] columns = new int[0];
  private int count;

  static StatusCode validateClaim(
      boolean active,
      SqlQueryExecution execution,
      long generation,
      int[] source,
      int sourceCount,
      long rowLimit) {
    if (active
        || execution == null
        || generation <= 0
        || source == null
        || sourceCount <= 0
        || sourceCount > source.length
        || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    return sourceCount > SqlShapeLimits.MAX_RESULT_COLUMNS
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  StatusCode set(int[] source, int sourceCount) {
    if (sourceCount > columns.length) {
      int capacity = BoundedArrayGrowth.capacity(
          columns.length, sourceCount, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
      try {
        columns = new int[capacity];
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    System.arraycopy(source, 0, columns, 0, sourceCount);
    count = sourceCount;
    return StatusCode.OK;
  }

  int get(int index) {
    return index >= 0 && index < count ? columns[index] : -1;
  }

  int count() {
    return count;
  }

  void reset() {
    count = 0;
  }
}
