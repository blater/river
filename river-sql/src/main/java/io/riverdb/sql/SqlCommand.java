package io.riverdb.sql;

/** Caller-owned parsed SQL command for the first executable point-statement subset. */
public final class SqlCommand {
  public static final int MAXIMUM_INSERT_ROWS = 64;
  public static final int MAXIMUM_COLUMNS = 8;

  private final SqlIdentifier tableName = new SqlIdentifier();
  private final SqlIdentifier joinTableName = new SqlIdentifier();
  private final SqlIdentifier joinOuterColumnName = new SqlIdentifier();
  private final SqlIdentifier joinInnerColumnName = new SqlIdentifier();
  private final SqlIdentifier indexName = new SqlIdentifier();
  private final SqlIdentifier savepointName = new SqlIdentifier();
  private final SqlIdentifier[] columnNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnTableNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier predicateTableName = new SqlIdentifier();
  private final SqlIdentifier predicateColumnName = new SqlIdentifier();
  private final SqlIdentifier orderColumnName = new SqlIdentifier();
  private final long[] insertValues =
      new long[MAXIMUM_INSERT_ROWS * MAXIMUM_COLUMNS];
  private final long[] updateValues = new long[MAXIMUM_COLUMNS];
  private SqlCommandType type;
  private long key;
  private long value;
  private long scanLowerInclusive;
  private long scanUpperExclusive;
  private long rowLimit = Long.MAX_VALUE;
  private boolean boundedScan;
  private boolean equalityPredicate;
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
      columnTableNames[index] = new SqlIdentifier();
    }
  }

  public void reset() {
    tableName.reset();
    joinTableName.reset();
    joinOuterColumnName.reset();
    joinInnerColumnName.reset();
    indexName.reset();
    savepointName.reset();
    for (SqlIdentifier columnName : columnNames) {
      columnName.reset();
    }
    for (SqlIdentifier columnTableName : columnTableNames) {
      columnTableName.reset();
    }
    predicateTableName.reset();
    predicateColumnName.reset();
    orderColumnName.reset();
    type = null;
    key = 0;
    value = 0;
    scanLowerInclusive = 0;
    scanUpperExclusive = 0;
    rowLimit = Long.MAX_VALUE;
    boundedScan = false;
    equalityPredicate = false;
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

  void setPredicate(
      long equalityValue,
      long lowerInclusive,
      long upperExclusive,
      boolean bounded,
      boolean equality) {
    key = equalityValue;
    scanLowerInclusive = lowerInclusive;
    scanUpperExclusive = upperExclusive;
    boundedScan = bounded;
    equalityPredicate = equality;
  }

  void setSelectAll() {
    selectAll = true;
  }

  void setRowLimit(long maximumRows) {
    rowLimit = maximumRows;
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

  SqlIdentifier writableJoinTableName() {
    return joinTableName;
  }

  SqlIdentifier writableJoinOuterColumnName() {
    return joinOuterColumnName;
  }

  SqlIdentifier writableJoinInnerColumnName() {
    return joinInnerColumnName;
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

  SqlIdentifier writableColumnTableName(int index) {
    return index >= 0 && index < columnCount ? columnTableNames[index] : null;
  }

  SqlIdentifier writablePredicateColumnName() {
    return predicateColumnName;
  }

  SqlIdentifier writablePredicateTableName() {
    return predicateTableName;
  }

  SqlIdentifier writableOrderColumnName() {
    return orderColumnName;
  }

  public SqlCommandType type() {
    return type;
  }

  public SqlIdentifier tableName() {
    return tableName;
  }

  public SqlIdentifier joinTableName() {
    return joinTableName;
  }

  public SqlIdentifier joinOuterColumnName() {
    return joinOuterColumnName;
  }

  public SqlIdentifier joinInnerColumnName() {
    return joinInnerColumnName;
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

  public SqlIdentifier columnTableName(int index) {
    return index >= 0 && index < columnCount ? columnTableNames[index] : null;
  }

  public SqlIdentifier predicateColumnName() {
    return predicateColumnName;
  }

  public SqlIdentifier predicateTableName() {
    return predicateTableName;
  }

  public SqlIdentifier orderColumnName() {
    return orderColumnName;
  }

  public boolean isOrdered() {
    return orderColumnName.length() > 0;
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

  public boolean hasPredicate() {
    return predicateColumnName.length() > 0;
  }

  public boolean isEqualityPredicate() {
    return equalityPredicate;
  }

  public boolean isSelectAll() {
    return selectAll;
  }

  public boolean isSerializableTransaction() {
    return serializableTransaction;
  }

  public long rowLimit() {
    return rowLimit;
  }

  public boolean isAvailable() {
    return available;
  }
}
