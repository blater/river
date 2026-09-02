package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Retained actual-count primitive storage for one reusable statistics carrier. */
final class TableStatisticsStorage {
  long[] nullCounts = new long[0];
  long[] distinctCounts = new long[0];
  long[] minimumValues = new long[0];
  long[] maximumValues = new long[0];
  final ColumnBitSet minMaxColumns = new ColumnBitSet();
  final ColumnBitSet sampledColumns = new ColumnBitSet();

  StatusCode reserve(int columns, int used) {
    if (columns < 0 || columns > SqlShapeLimits.MAX_TABLE_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (columns <= nullCounts.length) return reserveBits(columns);
    int capacity = BoundedArrayGrowth.capacity(
        nullCounts.length, columns, SqlShapeLimits.MAX_TABLE_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = reserveBits(capacity);
    if (!status.isOk()) return status;
    try {
      long[] newNulls = new long[capacity];
      long[] newDistinct = new long[capacity];
      long[] newMinimums = new long[capacity];
      long[] newMaximums = new long[capacity];
      System.arraycopy(nullCounts, 0, newNulls, 0, used);
      System.arraycopy(distinctCounts, 0, newDistinct, 0, used);
      System.arraycopy(minimumValues, 0, newMinimums, 0, used);
      System.arraycopy(maximumValues, 0, newMaximums, 0, used);
      nullCounts = newNulls;
      distinctCounts = newDistinct;
      minimumValues = newMinimums;
      maximumValues = newMaximums;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void reset(int used) {
    for (int column = 0; column < used; column++) {
      nullCounts[column] = 0;
      distinctCounts[column] = 0;
      minimumValues[column] = 0;
      maximumValues[column] = 0;
    }
    minMaxColumns.reset();
    sampledColumns.reset();
  }

  boolean canonical(int columns, long rows) {
    for (int column = 0; column < columns; column++) {
      long nulls = nullCounts[column];
      long distinct = distinctCounts[column];
      long nonNull = rows - nulls;
      if (nulls < 0 || nulls > rows || distinct < 0 || distinct > nonNull
          || (distinct == 0) != (nonNull == 0)
          || nonNull == 0 && minMaxColumns.get(column)
          || !minMaxColumns.get(column)
              && (minimumValues[column] != 0 || maximumValues[column] != 0)) {
        return false;
      }
    }
    return true;
  }

  private StatusCode reserveBits(int columns) {
    StatusCode status = minMaxColumns.reserve(columns, SqlShapeLimits.MAX_TABLE_COLUMNS);
    return status.isOk()
        ? sampledColumns.reserve(columns, SqlShapeLimits.MAX_TABLE_COLUMNS) : status;
  }
}
