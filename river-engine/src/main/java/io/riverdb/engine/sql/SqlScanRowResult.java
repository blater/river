package io.riverdb.engine.sql;

import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.TableSchema;
import java.nio.ByteBuffer;

/** Caller-owned decoded `KEY`, `VALUE` row returned by an SQL scan. */
public final class SqlScanRowResult {
  private final RelationalScanResult relational = new RelationalScanResult();
  private final ByteBuffer valueBytes = ByteBuffer.allocateDirect(
      (TableSchema.MAXIMUM_COLUMNS - 1) * Long.BYTES);
  private long key;
  private long value;
  private boolean available;

  public void reset() {
    relational.reset();
    valueBytes.clear();
    key = 0;
    value = 0;
    available = false;
  }

  RelationalScanResult relational() {
    return relational;
  }

  ByteBuffer valueBytes() {
    return valueBytes;
  }

  void set(long rowKey, long rowValue) {
    key = rowKey;
    value = rowValue;
    available = true;
  }

  public long key() {
    return key;
  }

  public long value() {
    return value;
  }

  public boolean isAvailable() {
    return available;
  }
}
