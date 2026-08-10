package io.riverdb.engine.sql;

import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.TableSchema;

/** Caller-owned decoded `KEY`, `VALUE` row returned by an SQL scan. */
public final class SqlScanRowResult {
  private final RelationalScanResult relational = new RelationalScanResult();
  private final long[] values = new long[TableSchema.MAXIMUM_COLUMNS];
  private long key;
  private long value;
  private int columnCount;
  private boolean available;

  public void reset() {
    relational.reset();
    key = 0;
    value = 0;
    columnCount = 0;
    available = false;
  }

  RelationalScanResult relational() {
    return relational;
  }

  void set(long rowKey, long[] projectedValues, int projectedColumnCount) {
    key = rowKey;
    columnCount = projectedColumnCount;
    for (int index = 0; index < projectedColumnCount; index++) {
      values[index] = projectedValues[index];
    }
    value = projectedColumnCount == 0 ? 0 : values[projectedColumnCount - 1];
    available = true;
  }

  public long key() {
    return key;
  }

  public long value() {
    return value;
  }

  public int columnCount() {
    return columnCount;
  }

  public long valueAt(int index) {
    return index >= 0 && index < columnCount ? values[index] : 0;
  }

  public boolean isAvailable() {
    return available;
  }
}
