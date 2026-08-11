package io.riverdb.sql;

/** Caller-owned parsed SQL command for the first executable point-statement subset. */
public final class SqlCommand {
  public static final int MAXIMUM_INSERT_ROWS = 64;
  public static final int MAXIMUM_COLUMNS = 8;
  public static final int MAXIMUM_PREDICATES = MAXIMUM_COLUMNS;

  private final SqlIdentifier tableName = new SqlIdentifier();
  private final SqlIdentifier tableAlias = new SqlIdentifier();
  private final SqlIdentifier joinTableName = new SqlIdentifier();
  private final SqlIdentifier joinOuterColumnName = new SqlIdentifier();
  private final SqlIdentifier joinInnerColumnName = new SqlIdentifier();
  private final SqlIdentifier indexName = new SqlIdentifier();
  private final SqlIdentifier savepointName = new SqlIdentifier();
  private final SqlIdentifier[] columnNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnTableNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnAliases = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] predicateTableNames =
      new SqlIdentifier[MAXIMUM_PREDICATES];
  private final SqlIdentifier[] predicateColumnNames =
      new SqlIdentifier[MAXIMUM_PREDICATES];
  private final SqlIdentifier[] predicateValueTableNames =
      new SqlIdentifier[MAXIMUM_PREDICATES];
  private final SqlIdentifier[] predicateValueColumnNames =
      new SqlIdentifier[MAXIMUM_PREDICATES];
  private final SqlIdentifier orderColumnName = new SqlIdentifier();
  private final long[] insertValues =
      new long[MAXIMUM_INSERT_ROWS * MAXIMUM_COLUMNS];
  private final long[] insertNullMasks = new long[MAXIMUM_INSERT_ROWS];
  private final long[] updateValues = new long[MAXIMUM_COLUMNS];
  private final boolean[] nullUpdates = new boolean[MAXIMUM_COLUMNS];
  private final long[] predicateValues = new long[MAXIMUM_PREDICATES];
  private final long[] predicateLowerInclusive = new long[MAXIMUM_PREDICATES];
  private final long[] predicateUpperExclusive = new long[MAXIMUM_PREDICATES];
  private final boolean[] equalityPredicates = new boolean[MAXIMUM_PREDICATES];
  private final boolean[] columnPredicates = new boolean[MAXIMUM_PREDICATES];
  private final boolean[] nullPredicates = new boolean[MAXIMUM_PREDICATES];
  private final boolean[] negatedNullPredicates =
      new boolean[MAXIMUM_PREDICATES];
  private final boolean[] nullProjections = new boolean[MAXIMUM_COLUMNS];
  private SqlCommandType type;
  private long key;
  private long value;
  private long scanLowerInclusive;
  private long scanUpperExclusive;
  private long rowLimit = Long.MAX_VALUE;
  private boolean boundedScan;
  private boolean equalityPredicate;
  private boolean selectAll;
  private boolean readCommittedTransaction;
  private boolean serializableTransaction;
  private int insertRowCount;
  private int insertColumnCount;
  private int updateColumnCount;
  private int predicateCount;
  private int columnCount;
  private boolean available;

  public SqlCommand() {
    for (int index = 0; index < columnNames.length; index++) {
      columnNames[index] = new SqlIdentifier();
      columnTableNames[index] = new SqlIdentifier();
      columnAliases[index] = new SqlIdentifier();
      predicateTableNames[index] = new SqlIdentifier();
      predicateColumnNames[index] = new SqlIdentifier();
      predicateValueTableNames[index] = new SqlIdentifier();
      predicateValueColumnNames[index] = new SqlIdentifier();
    }
  }

  public void reset() {
    tableName.reset();
    tableAlias.reset();
    joinTableName.reset();
    joinOuterColumnName.reset();
    joinInnerColumnName.reset();
    indexName.reset();
    savepointName.reset();
    for (SqlIdentifier columnName : columnNames) {
      columnName.reset();
    }
    for (int index = 0; index < nullProjections.length; index++) {
      nullProjections[index] = false;
    }
    for (SqlIdentifier columnTableName : columnTableNames) {
      columnTableName.reset();
    }
    for (SqlIdentifier columnAlias : columnAliases) {
      columnAlias.reset();
    }
    for (int index = 0; index < predicateColumnNames.length; index++) {
      predicateTableNames[index].reset();
      predicateColumnNames[index].reset();
      predicateValueTableNames[index].reset();
      predicateValueColumnNames[index].reset();
      predicateValues[index] = 0;
      predicateLowerInclusive[index] = 0;
      predicateUpperExclusive[index] = 0;
      equalityPredicates[index] = false;
      columnPredicates[index] = false;
      nullPredicates[index] = false;
      negatedNullPredicates[index] = false;
    }
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
    readCommittedTransaction = false;
    serializableTransaction = false;
    insertRowCount = 0;
    insertColumnCount = 0;
    updateColumnCount = 0;
    predicateCount = 0;
    columnCount = 0;
    available = false;
    for (int index = 0; index < insertNullMasks.length; index++) {
      insertNullMasks[index] = 0;
    }
    for (int index = 0; index < nullUpdates.length; index++) {
      nullUpdates[index] = false;
    }
  }

  void set(SqlCommandType commandType, long primaryKey, long rowValue) {
    type = commandType;
    if (predicateCount == 0) {
      key = primaryKey;
    }
    value = rowValue;
    available = true;
  }

  void setScan(long lowerInclusive, long upperExclusive, boolean bounded) {
    type = SqlCommandType.SCAN;
    if (predicateCount == 0) {
      scanLowerInclusive = lowerInclusive;
      scanUpperExclusive = upperExclusive;
      boundedScan = bounded;
    }
    available = true;
  }

  void appendPredicate(
      long equalityValue,
      long lowerInclusive,
      long upperExclusive,
      boolean equality) {
    int index = predicateCount++;
    predicateValues[index] = equalityValue;
    predicateLowerInclusive[index] = lowerInclusive;
    predicateUpperExclusive[index] = upperExclusive;
    equalityPredicates[index] = equality;
    if (index == 0) {
      key = equalityValue;
      scanLowerInclusive = lowerInclusive;
      scanUpperExclusive = upperExclusive;
      boundedScan = !equality;
      equalityPredicate = equality;
    }
  }

  void appendNullPredicate(boolean negated) {
    int index = predicateCount++;
    nullPredicates[index] = true;
    negatedNullPredicates[index] = negated;
    if (index == 0) {
      equalityPredicate = false;
      boundedScan = false;
    }
  }

  void appendColumnPredicate() {
    int index = predicateCount++;
    equalityPredicates[index] = true;
    columnPredicates[index] = true;
    if (index == 0) {
      equalityPredicate = true;
      boundedScan = false;
    }
  }

  void setSelectAll() {
    selectAll = true;
  }

  void setRowLimit(long maximumRows) {
    rowLimit = maximumRows;
  }

  void setPredicateValue(int index, long predicateValue) {
    if (index >= 0 && index < predicateCount && equalityPredicates[index]) {
      predicateValues[index] = predicateValue;
      if (index == 0) {
        key = predicateValue;
      }
    }
  }

  void copyQueryFrom(SqlCommand source) {
    reset();
    tableName.copyFrom(source.tableName);
    tableAlias.copyFrom(source.tableAlias);
    for (int index = 0; index < source.columnCount; index++) {
      writableNextColumnName().copyFrom(source.columnNames[index]);
      writableColumnTableName(index).copyFrom(source.columnTableNames[index]);
      writableColumnAlias(index).copyFrom(source.columnAliases[index]);
      nullProjections[index] = source.nullProjections[index];
    }
    for (int index = 0; index < source.predicateCount; index++) {
      writableNextPredicateTableName().copyFrom(source.predicateTableNames[index]);
      writableNextPredicateColumnName().copyFrom(source.predicateColumnNames[index]);
      if (source.nullPredicates[index]) {
        appendNullPredicate(source.negatedNullPredicates[index]);
      } else if (source.columnPredicates[index]) {
        writableNextPredicateValueTableName().copyFrom(
            source.predicateValueTableNames[index]);
        writableNextPredicateValueColumnName().copyFrom(
            source.predicateValueColumnNames[index]);
        appendColumnPredicate();
      } else {
        appendPredicate(
            source.predicateValues[index],
            source.predicateLowerInclusive[index],
            source.predicateUpperExclusive[index],
            source.equalityPredicates[index]);
      }
    }
    orderColumnName.copyFrom(source.orderColumnName);
    if (source.selectAll) {
      setSelectAll();
    }
    setRowLimit(source.rowLimit);
    if (source.type == SqlCommandType.SCAN) {
      setScan(source.scanLowerInclusive, source.scanUpperExclusive, source.boundedScan);
    } else {
      set(source.type, source.key, source.value);
    }
  }

  void setBegin(boolean readCommitted, boolean serializable) {
    type = SqlCommandType.BEGIN;
    readCommittedTransaction = readCommitted;
    serializableTransaction = serializable;
    available = true;
  }

  void appendInsert(long[] values, long nullMask, int count) {
    int destination = insertRowCount * MAXIMUM_COLUMNS;
    for (int index = 0; index < count; index++) {
      insertValues[destination + index] = values[index];
    }
    insertColumnCount = count;
    insertNullMasks[insertRowCount] = nullMask;
    insertRowCount++;
  }

  void setInsert() {
    type = SqlCommandType.INSERT;
    key = insertValues[0];
    value = insertValues[1];
    available = true;
  }

  void appendUpdate(long updateValue, boolean isNull) {
    updateValues[updateColumnCount] = updateValue;
    nullUpdates[updateColumnCount++] = isNull;
  }

  SqlIdentifier writableTableName() {
    return tableName;
  }

  SqlIdentifier writableTableAlias() {
    return tableAlias;
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

  void markLastProjectionNull() {
    if (columnCount > 0) {
      nullProjections[columnCount - 1] = true;
    }
  }

  SqlIdentifier writableColumnTableName(int index) {
    return index >= 0 && index < columnCount ? columnTableNames[index] : null;
  }

  SqlIdentifier writableColumnAlias(int index) {
    return index >= 0 && index < columnCount ? columnAliases[index] : null;
  }

  SqlIdentifier writableNextPredicateColumnName() {
    return predicateCount < predicateColumnNames.length
        ? predicateColumnNames[predicateCount] : null;
  }

  SqlIdentifier writableNextPredicateTableName() {
    return predicateCount < predicateTableNames.length
        ? predicateTableNames[predicateCount] : null;
  }

  SqlIdentifier writableNextPredicateValueColumnName() {
    return predicateCount < predicateValueColumnNames.length
        ? predicateValueColumnNames[predicateCount] : null;
  }

  SqlIdentifier writableNextPredicateValueTableName() {
    return predicateCount < predicateValueTableNames.length
        ? predicateValueTableNames[predicateCount] : null;
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

  public SqlIdentifier tableAlias() {
    return tableAlias;
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

  public SqlIdentifier columnOutputName(int index) {
    if (index < 0 || index >= columnCount) {
      return null;
    }
    return columnAliases[index].length() > 0
        ? columnAliases[index] : columnNames[index];
  }

  public boolean isNullProjection(int index) {
    return index >= 0 && index < columnCount && nullProjections[index];
  }

  public SqlIdentifier predicateColumnName() {
    return predicateColumnName(0);
  }

  public SqlIdentifier predicateTableName() {
    return predicateTableName(0);
  }

  public int predicateCount() {
    return predicateCount;
  }

  public SqlIdentifier predicateColumnName(int index) {
    return index >= 0 && index < predicateCount ? predicateColumnNames[index] : null;
  }

  public SqlIdentifier predicateTableName(int index) {
    return index >= 0 && index < predicateCount ? predicateTableNames[index] : null;
  }

  public SqlIdentifier predicateValueColumnName(int index) {
    return index >= 0 && index < predicateCount
        ? predicateValueColumnNames[index] : null;
  }

  public SqlIdentifier predicateValueTableName(int index) {
    return index >= 0 && index < predicateCount
        ? predicateValueTableNames[index] : null;
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

  public boolean insertIsNull(int rowIndex, int columnIndex) {
    return rowIndex >= 0
        && rowIndex < insertRowCount
        && columnIndex >= 0
        && columnIndex < insertColumnCount
        && (insertNullMasks[rowIndex] & 1L << columnIndex) != 0;
  }

  public boolean updateIsNull(int index) {
    return index >= 0 && index < updateColumnCount && nullUpdates[index];
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
    return predicateCount > 0;
  }

  public boolean isEqualityPredicate() {
    return equalityPredicate;
  }

  public boolean isEqualityPredicate(int index) {
    return index >= 0 && index < predicateCount && equalityPredicates[index];
  }

  public boolean isNullPredicate(int index) {
    return index >= 0 && index < predicateCount && nullPredicates[index];
  }

  public boolean isColumnPredicate(int index) {
    return index >= 0 && index < predicateCount && columnPredicates[index];
  }

  public boolean isNullPredicateNegated(int index) {
    return index >= 0
        && index < predicateCount
        && nullPredicates[index]
        && negatedNullPredicates[index];
  }

  public long predicateValue(int index) {
    return index >= 0 && index < predicateCount ? predicateValues[index] : 0;
  }

  public long predicateLowerInclusive(int index) {
    return index >= 0 && index < predicateCount ? predicateLowerInclusive[index] : 0;
  }

  public long predicateUpperExclusive(int index) {
    return index >= 0 && index < predicateCount ? predicateUpperExclusive[index] : 0;
  }

  public boolean isSelectAll() {
    return selectAll;
  }

  public boolean isSerializableTransaction() {
    return serializableTransaction;
  }

  public boolean isReadCommittedTransaction() {
    return readCommittedTransaction;
  }

  public long rowLimit() {
    return rowLimit;
  }

  public boolean isAvailable() {
    return available;
  }
}
