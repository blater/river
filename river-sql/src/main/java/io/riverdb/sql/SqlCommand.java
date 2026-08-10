package io.riverdb.sql;

/** Caller-owned parsed SQL command for the first executable point-statement subset. */
public final class SqlCommand {
  private final SqlIdentifier tableName = new SqlIdentifier();
  private SqlCommandType type;
  private long key;
  private long value;
  private long scanLowerInclusive;
  private long scanUpperExclusive;
  private boolean boundedScan;
  private boolean serializableTransaction;
  private boolean available;

  public void reset() {
    tableName.reset();
    type = null;
    key = 0;
    value = 0;
    scanLowerInclusive = 0;
    scanUpperExclusive = 0;
    boundedScan = false;
    serializableTransaction = false;
    available = false;
  }

  void set(SqlCommandType commandType, long primaryKey, long rowValue) {
    type = commandType;
    key = primaryKey;
    value = rowValue;
    available = true;
  }

  void setScan(long lowerInclusive, long upperExclusive, boolean bounded) {
    type = SqlCommandType.SCAN;
    scanLowerInclusive = lowerInclusive;
    scanUpperExclusive = upperExclusive;
    boundedScan = bounded;
    available = true;
  }

  void setBegin(boolean serializable) {
    type = SqlCommandType.BEGIN;
    serializableTransaction = serializable;
    available = true;
  }

  SqlIdentifier writableTableName() {
    return tableName;
  }

  public SqlCommandType type() {
    return type;
  }

  public SqlIdentifier tableName() {
    return tableName;
  }

  public long key() {
    return key;
  }

  public long value() {
    return value;
  }

  public long scanLowerInclusive() {
    return scanLowerInclusive;
  }

  public long scanUpperExclusive() {
    return scanUpperExclusive;
  }

  public boolean isBoundedScan() {
    return boundedScan;
  }

  public boolean isSerializableTransaction() {
    return serializableTransaction;
  }

  public boolean isAvailable() {
    return available;
  }
}
