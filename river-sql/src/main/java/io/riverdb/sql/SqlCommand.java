package io.riverdb.sql;

/** Caller-owned parsed SQL command for the first executable point-statement subset. */
public final class SqlCommand {
  public static final int MAXIMUM_INSERT_ROWS = 64;

  private final SqlIdentifier tableName = new SqlIdentifier();
  private final SqlIdentifier indexName = new SqlIdentifier();
  private final SqlIdentifier savepointName = new SqlIdentifier();
  private final long[] insertKeys = new long[MAXIMUM_INSERT_ROWS];
  private final long[] insertValues = new long[MAXIMUM_INSERT_ROWS];
  private SqlCommandType type;
  private long key;
  private long value;
  private long scanLowerInclusive;
  private long scanUpperExclusive;
  private boolean boundedScan;
  private boolean serializableTransaction;
  private int insertRowCount;
  private boolean available;

  public void reset() {
    tableName.reset();
    indexName.reset();
    savepointName.reset();
    type = null;
    key = 0;
    value = 0;
    scanLowerInclusive = 0;
    scanUpperExclusive = 0;
    boundedScan = false;
    serializableTransaction = false;
    insertRowCount = 0;
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

  void setValueScan(long lowerInclusive, long upperExclusive) {
    type = SqlCommandType.VALUE_SCAN;
    scanLowerInclusive = lowerInclusive;
    scanUpperExclusive = upperExclusive;
    boundedScan = true;
    available = true;
  }

  void setBegin(boolean serializable) {
    type = SqlCommandType.BEGIN;
    serializableTransaction = serializable;
    available = true;
  }

  void appendInsert(long primaryKey, long rowValue) {
    insertKeys[insertRowCount] = primaryKey;
    insertValues[insertRowCount] = rowValue;
    insertRowCount++;
  }

  void setInsert() {
    type = SqlCommandType.INSERT;
    key = insertKeys[0];
    value = insertValues[0];
    available = true;
  }

  SqlIdentifier writableTableName() {
    return tableName;
  }

  SqlIdentifier writableIndexName() {
    return indexName;
  }

  SqlIdentifier writableSavepointName() {
    return savepointName;
  }

  public SqlCommandType type() {
    return type;
  }

  public SqlIdentifier tableName() {
    return tableName;
  }

  public SqlIdentifier indexName() {
    return indexName;
  }

  public SqlIdentifier savepointName() {
    return savepointName;
  }

  public long key() {
    return key;
  }

  public long value() {
    return value;
  }

  public int insertRowCount() {
    return insertRowCount;
  }

  public long insertKey(int index) {
    return index >= 0 && index < insertRowCount ? insertKeys[index] : 0;
  }

  public long insertValue(int index) {
    return index >= 0 && index < insertRowCount ? insertValues[index] : 0;
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
