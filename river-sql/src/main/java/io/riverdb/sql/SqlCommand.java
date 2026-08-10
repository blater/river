package io.riverdb.sql;

/** Caller-owned parsed SQL command for the first executable point-statement subset. */
public final class SqlCommand {
  public static final int MAXIMUM_INSERT_ROWS = 64;
  public static final int MAXIMUM_COLUMNS = 8;

  private final SqlIdentifier tableName = new SqlIdentifier();
  private final SqlIdentifier indexName = new SqlIdentifier();
  private final SqlIdentifier savepointName = new SqlIdentifier();
  private final SqlIdentifier[] columnNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier predicateColumnName = new SqlIdentifier();
  private final long[] insertValues =
      new long[MAXIMUM_INSERT_ROWS * MAXIMUM_COLUMNS];
  private final long[] updateValues = new long[MAXIMUM_COLUMNS];
  private SqlCommandType type;
  private long key;
  private long value;
  private long scanLowerInclusive;
  private long scanUpperExclusive;
  private boolean boundedScan;
  private boolean selectAll;
  private boolean serializableTransaction;
  private int insertRowCount;
  private int insertColumnCount;
  private int updateColumnCount;
  private int columnCount;
  private boolean available;

  public SqlCommand() {
    for (int index = 0; index < columnNames.length; index++) {
      columnNames[index] = new SqlIdentifier();
    }
  }

  public void reset() {
    tableName.reset();
    indexName.reset();
    savepointName.reset();
    for (SqlIdentifier columnName : columnNames) {
      columnName.reset();
    }
    predicateColumnName.reset();
    type = null;
    key = 0;
    value = 0;
    scanLowerInclusive = 0;
    scanUpperExclusive = 0;
    boundedScan = false;
    selectAll = false;
    serializableTransaction = false;
    insertRowCount = 0;
    insertColumnCount = 0;
    updateColumnCount = 0;
    columnCount = 0;
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

  void setSelectAll() {
    selectAll = true;
  }

  void setBegin(boolean serializable) {
    type = SqlCommandType.BEGIN;
    serializableTransaction = serializable;
    available = true;
  }

  void appendInsert(long[] values, int count) {
    int destination = insertRowCount * MAXIMUM_COLUMNS;
    for (int index = 0; index < count; index++) {
      insertValues[destination + index] = values[index];
    }
    insertColumnCount = count;
    insertRowCount++;
  }

  void setInsert() {
    type = SqlCommandType.INSERT;
    key = insertValues[0];
    value = insertValues[1];
    available = true;
  }

  void appendUpdate(long updateValue) {
    updateValues[updateColumnCount++] = updateValue;
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

  SqlIdentifier writableNextColumnName() {
    return columnCount < columnNames.length ? columnNames[columnCount++] : null;
  }

  SqlIdentifier writablePredicateColumnName() {
    return predicateColumnName;
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

  public SqlIdentifier firstColumnName() {
    return columnNames[0];
  }

  public SqlIdentifier secondColumnName() {
    return columnNames[1];
  }

  public int columnCount() {
    return columnCount;
  }

  public SqlIdentifier columnName(int index) {
    return index >= 0 && index < columnCount ? columnNames[index] : null;
  }

  public SqlIdentifier predicateColumnName() {
    return predicateColumnName;
  }

  public long key() {
    return key;
  }

  public long value() {
    return type == SqlCommandType.UPDATE && updateColumnCount > 0
        ? updateValues[0] : value;
  }

  public int updateColumnCount() {
    return updateColumnCount;
  }

  public long updateValue(int index) {
    return index >= 0 && index < updateColumnCount ? updateValues[index] : 0;
  }

  public int insertRowCount() {
    return insertRowCount;
  }

  public long insertKey(int index) {
    return insertValue(index, 0);
  }

  public long insertValue(int index) {
    return insertValue(index, 1);
  }

  public int insertColumnCount() {
    return insertColumnCount;
  }

  public long insertValue(int rowIndex, int columnIndex) {
    return rowIndex >= 0
            && rowIndex < insertRowCount
            && columnIndex >= 0
            && columnIndex < insertColumnCount
        ? insertValues[rowIndex * MAXIMUM_COLUMNS + columnIndex] : 0;
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

  public boolean isSelectAll() {
    return selectAll;
  }

  public boolean isSerializableTransaction() {
    return serializableTransaction;
  }

  public boolean isAvailable() {
    return available;
  }
}
