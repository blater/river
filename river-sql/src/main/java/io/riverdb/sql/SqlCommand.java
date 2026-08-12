package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Caller-owned parsed SQL command for the first executable point-statement subset. */
public final class SqlCommand {
  public static final int MAXIMUM_INSERT_ROWS = 64;
  public static final int MAXIMUM_COLUMNS = 8;
  public static final int MAXIMUM_CONSTRAINT_INDEXES = 4;
  public static final int MAXIMUM_PREDICATES = MAXIMUM_COLUMNS;
  public static final int MAXIMUM_LITERAL_MEMBERSHIP_VALUES = 256;
  public static final int MAXIMUM_VIEW_QUERY_LENGTH = 768;

  private final SqlIdentifier tableName = new SqlIdentifier();
  private final SqlIdentifier renamedTableName = new SqlIdentifier();
  private final SqlIdentifier tableAlias = new SqlIdentifier();
  private final SqlIdentifier joinTableName = new SqlIdentifier();
  private final SqlIdentifier joinTableAlias = new SqlIdentifier();
  private final SqlIdentifier joinOuterColumnName = new SqlIdentifier();
  private final SqlIdentifier joinInnerColumnName = new SqlIdentifier();
  private final SqlIdentifier indexName = new SqlIdentifier();
  private final SqlIdentifier renamedIndexName = new SqlIdentifier();
  private final SqlIdentifier sequenceName = new SqlIdentifier();
  private final SqlIdentifier savepointName = new SqlIdentifier();
  private final ViewQuery viewQuery = new ViewQuery();
  private final SqlIdentifier[] columnNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnTableNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnAliases = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnReferenceTableNames =
      new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnReferenceColumnNames =
      new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] updateSourceColumnNames =
      new SqlIdentifier[MAXIMUM_COLUMNS];
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
  private final long[] insertDefaultMasks = new long[MAXIMUM_INSERT_ROWS];
  private final long[] insertVarcharMasks = new long[MAXIMUM_INSERT_ROWS];
  private final long[] updateValues = new long[MAXIMUM_COLUMNS];
  private final long[] columnDefaultValues = new long[MAXIMUM_COLUMNS];
  private final long[] columnCheckValues = new long[MAXIMUM_COLUMNS];
  private final SqlComparison[] columnCheckComparisons =
      new SqlComparison[MAXIMUM_COLUMNS];
  private final boolean[] nullUpdates = new boolean[MAXIMUM_COLUMNS];
  private final boolean[] defaultUpdates = new boolean[MAXIMUM_COLUMNS];
  private final boolean[] varcharUpdates = new boolean[MAXIMUM_COLUMNS];
  private final boolean[] relativeUpdates = new boolean[MAXIMUM_COLUMNS];
  private final boolean[] subtractUpdates = new boolean[MAXIMUM_COLUMNS];
  private final long[] predicateValues = new long[MAXIMUM_PREDICATES];
  private final long[] predicateLowerInclusive = new long[MAXIMUM_PREDICATES];
  private final long[] predicateUpperExclusive = new long[MAXIMUM_PREDICATES];
  private final long[] literalMembershipValues =
      new long[MAXIMUM_LITERAL_MEMBERSHIP_VALUES];
  private final int[] literalMembershipOffsets = new int[MAXIMUM_PREDICATES];
  private final int[] literalMembershipCounts = new int[MAXIMUM_PREDICATES];
  private final boolean[] literalMembershipHasNull =
      new boolean[MAXIMUM_PREDICATES];
  private final SqlComparison[] comparisons = new SqlComparison[MAXIMUM_PREDICATES];
  private final boolean[] columnPredicates = new boolean[MAXIMUM_PREDICATES];
  private final boolean[] nullPredicates = new boolean[MAXIMUM_PREDICATES];
  private final boolean[] negatedNullPredicates =
      new boolean[MAXIMUM_PREDICATES];
  private final boolean[] varcharPredicates = new boolean[MAXIMUM_PREDICATES];
  private final boolean[] disjunctionPredicates =
      new boolean[MAXIMUM_PREDICATES];
  private final boolean[] nullProjections = new boolean[MAXIMUM_COLUMNS];
  private SqlCommandType type;
  private SqlComparison groupHavingComparison;
  private long key;
  private long value;
  private long groupHavingValue;
  private long scanLowerInclusive;
  private long scanUpperExclusive;
  private long columnNotNullMask;
  private long columnDefaultMask;
  private long columnVarcharMask;
  private long columnUniqueMask;
  private long columnReferenceMask;
  private long rowLimit = Long.MAX_VALUE;
  private long sequenceStart = 1;
  private long sequenceIncrement = 1;
  private boolean boundedScan;
  private boolean equalityPredicate;
  private boolean selectAll;
  private boolean readCommittedTransaction;
  private boolean serializableTransaction;
  private boolean descendingOrder;
  private boolean leftJoin;
  private boolean primaryKeyIdentity;
  private int insertRowCount;
  private int insertColumnCount;
  private int updateColumnCount;
  private int predicateCount;
  private int literalMembershipValueCount;
  private int columnCount;
  private boolean available;

  public SqlCommand() {
    for (int index = 0; index < columnNames.length; index++) {
      columnNames[index] = new SqlIdentifier();
      columnTableNames[index] = new SqlIdentifier();
      columnAliases[index] = new SqlIdentifier();
      columnReferenceTableNames[index] = new SqlIdentifier();
      columnReferenceColumnNames[index] = new SqlIdentifier();
      updateSourceColumnNames[index] = new SqlIdentifier();
      predicateTableNames[index] = new SqlIdentifier();
      predicateColumnNames[index] = new SqlIdentifier();
      predicateValueTableNames[index] = new SqlIdentifier();
      predicateValueColumnNames[index] = new SqlIdentifier();
    }
  }

  public void reset() {
    tableName.reset();
    renamedTableName.reset();
    tableAlias.reset();
    joinTableName.reset();
    joinTableAlias.reset();
    joinOuterColumnName.reset();
    joinInnerColumnName.reset();
    indexName.reset();
    renamedIndexName.reset();
    sequenceName.reset();
    savepointName.reset();
    viewQuery.reset();
    for (SqlIdentifier columnName : columnNames) {
      columnName.reset();
    }
    for (int index = 0; index < nullProjections.length; index++) {
      nullProjections[index] = false;
      columnCheckValues[index] = 0;
      columnCheckComparisons[index] = null;
      columnReferenceTableNames[index].reset();
      columnReferenceColumnNames[index].reset();
    }
    for (SqlIdentifier columnTableName : columnTableNames) {
      columnTableName.reset();
    }
    for (SqlIdentifier columnAlias : columnAliases) {
      columnAlias.reset();
    }
    for (SqlIdentifier updateSourceColumnName : updateSourceColumnNames) {
      updateSourceColumnName.reset();
    }
    for (int index = 0; index < predicateColumnNames.length; index++) {
      predicateTableNames[index].reset();
      predicateColumnNames[index].reset();
      predicateValueTableNames[index].reset();
      predicateValueColumnNames[index].reset();
      predicateValues[index] = 0;
      predicateLowerInclusive[index] = 0;
      predicateUpperExclusive[index] = 0;
      literalMembershipOffsets[index] = 0;
      literalMembershipCounts[index] = 0;
      literalMembershipHasNull[index] = false;
      comparisons[index] = null;
      columnPredicates[index] = false;
      nullPredicates[index] = false;
      negatedNullPredicates[index] = false;
      varcharPredicates[index] = false;
      disjunctionPredicates[index] = false;
    }
    orderColumnName.reset();
    type = null;
    groupHavingComparison = null;
    key = 0;
    value = 0;
    groupHavingValue = 0;
    scanLowerInclusive = 0;
    scanUpperExclusive = 0;
    columnNotNullMask = 0;
    columnDefaultMask = 0;
    columnVarcharMask = 0;
    columnUniqueMask = 0;
    columnReferenceMask = 0;
    rowLimit = Long.MAX_VALUE;
    sequenceStart = 1;
    sequenceIncrement = 1;
    boundedScan = false;
    equalityPredicate = false;
    selectAll = false;
    readCommittedTransaction = false;
    serializableTransaction = false;
    descendingOrder = false;
    leftJoin = false;
    primaryKeyIdentity = false;
    insertRowCount = 0;
    insertColumnCount = 0;
    updateColumnCount = 0;
    predicateCount = 0;
    literalMembershipValueCount = 0;
    columnCount = 0;
    available = false;
    for (int index = 0; index < insertNullMasks.length; index++) {
      insertNullMasks[index] = 0;
      insertDefaultMasks[index] = 0;
      insertVarcharMasks[index] = 0;
    }
    for (int index = 0; index < nullUpdates.length; index++) {
      nullUpdates[index] = false;
      defaultUpdates[index] = false;
      varcharUpdates[index] = false;
      relativeUpdates[index] = false;
      subtractUpdates[index] = false;
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

  void setLeftJoin() {
    leftJoin = true;
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
    comparisons[index] = equality
        ? SqlComparison.EQUAL : SqlComparison.HALF_OPEN_RANGE;
    if (index == 0) {
      key = equalityValue;
      scanLowerInclusive = lowerInclusive;
      scanUpperExclusive = upperExclusive;
      boundedScan = !equality;
      equalityPredicate = equality;
    }
  }

  void appendComparison(long predicateValue, SqlComparison comparison) {
    int index = predicateCount++;
    predicateValues[index] = predicateValue;
    comparisons[index] = comparison;
    if (index == 0) {
      key = predicateValue;
      equalityPredicate = comparison == SqlComparison.EQUAL;
      boundedScan = false;
    }
  }

  StatusCode appendLiteralMembership(
      long[] values,
      int count,
      boolean hasNull,
      boolean negated) {
    return appendLiteralMembership(values, 0, count, hasNull, negated);
  }

  private StatusCode appendLiteralMembership(
      long[] values,
      int valueOffset,
      int count,
      boolean hasNull,
      boolean negated) {
    if (values == null
        || valueOffset < 0
        || count < 0
        || valueOffset + count > values.length
        || count == 0 && !hasNull
        || literalMembershipValueCount + count > literalMembershipValues.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int index = predicateCount++;
    literalMembershipOffsets[index] = literalMembershipValueCount;
    literalMembershipHasNull[index] = hasNull;
    comparisons[index] = negated ? SqlComparison.NOT_IN : SqlComparison.IN;
    for (int value = 0; value < count; value++) {
      long candidate = values[valueOffset + value];
      int lower = literalMembershipOffsets[index];
      int upper = literalMembershipValueCount;
      while (lower < upper) {
        int middle = (lower + upper) >>> 1;
        if (literalMembershipValues[middle] < candidate) {
          lower = middle + 1;
        } else {
          upper = middle;
        }
      }
      if (lower < literalMembershipValueCount
          && literalMembershipValues[lower] == candidate) {
        continue;
      }
      for (int moved = literalMembershipValueCount; moved > lower; moved--) {
        literalMembershipValues[moved] = literalMembershipValues[moved - 1];
      }
      literalMembershipValues[lower] = candidate;
      literalMembershipValueCount++;
    }
    literalMembershipCounts[index] =
        literalMembershipValueCount - literalMembershipOffsets[index];
    if (index == 0) {
      equalityPredicate = false;
      boundedScan = false;
    }
    return StatusCode.OK;
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
    comparisons[index] = SqlComparison.EQUAL;
    columnPredicates[index] = true;
    if (index == 0) {
      equalityPredicate = true;
      boundedScan = false;
    }
  }

  void setSelectAll() {
    selectAll = true;
  }

  StatusCode expandSelectAllFrom(SqlCommand source) {
    if (!selectAll || columnCount != 0 || source == null || source.columnCount <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    selectAll = false;
    for (int index = 0; index < source.columnCount; index++) {
      SqlIdentifier column = writableNextColumnName();
      if (column == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      column.copyFrom(source.columnOutputName(index));
    }
    return StatusCode.OK;
  }

  void setRowLimit(long maximumRows) {
    rowLimit = maximumRows;
  }

  void setGroupHaving(SqlComparison comparison, long expected) {
    groupHavingComparison = comparison;
    groupHavingValue = expected;
  }

  void setPredicateValue(int index, long predicateValue) {
    if (index >= 0
        && index < predicateCount
        && comparisons[index] == SqlComparison.EQUAL) {
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
        if (source.isLiteralMembership(index)) {
          int offset = source.literalMembershipOffsets[index];
          int count = source.literalMembershipCounts[index];
          appendLiteralMembership(
              source.literalMembershipValues,
              offset,
              count,
              source.literalMembershipHasNull[index],
              source.comparisons[index] == SqlComparison.NOT_IN);
        } else if (source.comparisons[index] == SqlComparison.HALF_OPEN_RANGE) {
          appendPredicate(
              source.predicateValues[index],
              source.predicateLowerInclusive[index],
              source.predicateUpperExclusive[index],
              false);
        } else {
          appendComparison(source.predicateValues[index], source.comparisons[index]);
        }
      }
      if (source.varcharPredicates[index]) {
        markLastPredicateVarchar();
      }
      if (source.disjunctionPredicates[index]) {
        markLastPredicateDisjunction();
      }
    }
    orderColumnName.copyFrom(source.orderColumnName);
    descendingOrder = source.descendingOrder;
    groupHavingComparison = source.groupHavingComparison;
    groupHavingValue = source.groupHavingValue;
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

  void appendInsert(
      long[] values,
      long nullMask,
      long defaultMask,
      long varcharMask,
      int count) {
    int destination = insertRowCount * MAXIMUM_COLUMNS;
    for (int index = 0; index < count; index++) {
      insertValues[destination + index] = values[index];
    }
    insertColumnCount = count;
    insertNullMasks[insertRowCount] = nullMask;
    insertDefaultMasks[insertRowCount] = defaultMask;
    insertVarcharMasks[insertRowCount] = varcharMask;
    insertRowCount++;
  }

  void setInsert() {
    type = SqlCommandType.INSERT;
    key = insertValues[0];
    value = insertValues[1];
    available = true;
  }

  void appendUpdate(
      long updateValue,
      boolean isNull,
      boolean isDefault,
      boolean isVarchar,
      boolean relative,
      boolean subtract) {
    updateValues[updateColumnCount] = updateValue;
    nullUpdates[updateColumnCount] = isNull;
    defaultUpdates[updateColumnCount] = isDefault;
    varcharUpdates[updateColumnCount] = isVarchar;
    relativeUpdates[updateColumnCount] = relative;
    subtractUpdates[updateColumnCount++] = subtract;
  }

  SqlIdentifier writableNextUpdateSourceColumnName() {
    return updateColumnCount < updateSourceColumnNames.length
        ? updateSourceColumnNames[updateColumnCount] : null;
  }

  SqlIdentifier writableTableName() {
    return tableName;
  }

  SqlIdentifier writableRenamedTableName() {
    return renamedTableName;
  }

  SqlIdentifier writableTableAlias() {
    return tableAlias;
  }

  SqlIdentifier writableJoinTableName() {
    return joinTableName;
  }

  SqlIdentifier writableJoinTableAlias() {
    return joinTableAlias;
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

  SqlIdentifier writableRenamedIndexName() {
    return renamedIndexName;
  }

  SqlIdentifier writableSequenceName() {
    return sequenceName;
  }

  void setSequenceOptions(long start, long increment) {
    sequenceStart = start;
    sequenceIncrement = increment;
  }

  SqlIdentifier writableSavepointName() {
    return savepointName;
  }

  StatusCode setViewQuery(CharSequence sql, int start, int end) {
    return viewQuery.set(sql, start, end);
  }

  SqlIdentifier writableNextColumnName() {
    return columnCount < columnNames.length ? columnNames[columnCount++] : null;
  }

  void markLastColumnNotNull() {
    if (columnCount > 0) {
      columnNotNullMask |= 1L << columnCount - 1;
    }
  }

  void markPrimaryKeyIdentity() {
    primaryKeyIdentity = true;
  }

  void markLastColumnDefault(long value) {
    if (columnCount > 1) {
      int column = columnCount - 1;
      columnDefaultMask |= 1L << column;
      columnDefaultValues[column] = value;
    }
  }

  void markLastColumnVarchar() {
    if (columnCount > 1) {
      columnVarcharMask |= 1L << columnCount - 1;
    }
  }

  StatusCode markLastColumnUnique() {
    long bit = columnCount <= 0 ? 0 : 1L << columnCount - 1;
    if (columnCount <= 1
        || Long.bitCount(columnUniqueMask | columnReferenceMask | bit)
            > MAXIMUM_CONSTRAINT_INDEXES
        || (columnUniqueMask & bit) != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    columnUniqueMask |= bit;
    return StatusCode.OK;
  }

  SqlIdentifier writableLastColumnReferenceTableName() {
    return columnCount > 1 ? columnReferenceTableNames[columnCount - 1] : null;
  }

  SqlIdentifier writableLastColumnReferenceColumnName() {
    return columnCount > 1 ? columnReferenceColumnNames[columnCount - 1] : null;
  }

  StatusCode markLastColumnReference() {
    long bit = columnCount <= 0 ? 0 : 1L << columnCount - 1;
    if (columnCount <= 1
        || columnIsVarchar(columnCount - 1)
        || Long.bitCount(columnUniqueMask | columnReferenceMask | bit)
            > MAXIMUM_CONSTRAINT_INDEXES
        || (columnReferenceMask & bit) != 0
        || columnReferenceTableNames[columnCount - 1].length() == 0
        || columnReferenceColumnNames[columnCount - 1].length() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    columnReferenceMask |= bit;
    return StatusCode.OK;
  }

  void markLastColumnCheck(SqlComparison comparison, long value) {
    if (columnCount > 0) {
      int column = columnCount - 1;
      columnCheckComparisons[column] = comparison;
      columnCheckValues[column] = value;
    }
  }

  void markLastPredicateVarchar() {
    if (predicateCount > 0) {
      varcharPredicates[predicateCount - 1] = true;
    }
  }

  void markLastPredicateDisjunction() {
    if (predicateCount > 1) {
      disjunctionPredicates[predicateCount - 1] = true;
      equalityPredicate = false;
      boundedScan = false;
    }
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

  public SqlIdentifier renamedTableName() {
    return renamedTableName;
  }

  public SqlIdentifier tableAlias() {
    return tableAlias;
  }

  public SqlIdentifier joinTableName() {
    return joinTableName;
  }

  public SqlIdentifier joinTableAlias() {
    return joinTableAlias;
  }

  public SqlIdentifier joinOuterColumnName() {
    return joinOuterColumnName;
  }

  public SqlIdentifier joinInnerColumnName() {
    return joinInnerColumnName;
  }

  public boolean isLeftJoin() {
    return leftJoin;
  }

  public SqlIdentifier indexName() {
    return indexName;
  }

  public SqlIdentifier renamedIndexName() {
    return renamedIndexName;
  }

  public SqlIdentifier sequenceName() {
    return sequenceName;
  }

  public long sequenceStart() {
    return sequenceStart;
  }

  public long sequenceIncrement() {
    return sequenceIncrement;
  }

  public SqlIdentifier savepointName() {
    return savepointName;
  }

  public CharSequence viewQuery() {
    return viewQuery;
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

  public boolean columnIsNotNull(int index) {
    return index >= 0
        && index < columnCount
        && (columnNotNullMask & 1L << index) != 0;
  }

  public boolean columnHasDefault(int index) {
    return index > 0
        && index < columnCount
        && (columnDefaultMask & 1L << index) != 0;
  }

  public long columnDefaultValue(int index) {
    return columnHasDefault(index) ? columnDefaultValues[index] : 0;
  }

  public boolean columnIsVarchar(int index) {
    return index > 0
        && index < columnCount
        && (columnVarcharMask & 1L << index) != 0;
  }

  public boolean columnIsUnique(int index) {
    return index > 0
        && index < columnCount
        && (columnUniqueMask & 1L << index) != 0;
  }

  public boolean hasUniqueColumns() {
    return columnUniqueMask != 0;
  }

  public boolean columnHasReference(int index) {
    return index > 0
        && index < columnCount
        && (columnReferenceMask & 1L << index) != 0;
  }

  public SqlIdentifier columnReferenceTableName(int index) {
    return columnHasReference(index) ? columnReferenceTableNames[index] : null;
  }

  public SqlIdentifier columnReferenceColumnName(int index) {
    return columnHasReference(index) ? columnReferenceColumnNames[index] : null;
  }

  public boolean hasReferences() {
    return columnReferenceMask != 0;
  }

  public boolean hasPrimaryKeyIdentity() {
    return primaryKeyIdentity;
  }

  public boolean columnHasCheck(int index) {
    return index >= 0
        && index < columnCount
        && columnCheckComparisons[index] != null;
  }

  public SqlComparison columnCheckComparison(int index) {
    return columnHasCheck(index) ? columnCheckComparisons[index] : null;
  }

  public long columnCheckValue(int index) {
    return columnHasCheck(index) ? columnCheckValues[index] : 0;
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

  public SqlIdentifier columnAlias(int index) {
    return index >= 0 && index < columnCount ? columnAliases[index] : null;
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

  void setDescendingOrder(boolean descending) {
    descendingOrder = descending;
  }

  public boolean isDescendingOrder() {
    return descendingOrder;
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

  public SqlIdentifier updateSourceColumnName(int index) {
    return index >= 0 && index < updateColumnCount
        ? updateSourceColumnNames[index] : null;
  }

  public boolean isRelativeUpdate(int index) {
    return index >= 0 && index < updateColumnCount && relativeUpdates[index];
  }

  public boolean isSubtractUpdate(int index) {
    return index >= 0 && index < updateColumnCount && subtractUpdates[index];
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

  public boolean insertIsDefault(int rowIndex, int columnIndex) {
    return rowIndex >= 0
        && rowIndex < insertRowCount
        && columnIndex >= 0
        && columnIndex < insertColumnCount
        && (insertDefaultMasks[rowIndex] & 1L << columnIndex) != 0;
  }

  public boolean insertIsVarchar(int rowIndex, int columnIndex) {
    return rowIndex >= 0
        && rowIndex < insertRowCount
        && columnIndex >= 0
        && columnIndex < insertColumnCount
        && (insertVarcharMasks[rowIndex] & 1L << columnIndex) != 0;
  }

  public boolean updateIsNull(int index) {
    return index >= 0 && index < updateColumnCount && nullUpdates[index];
  }

  public boolean updateIsDefault(int index) {
    return index >= 0 && index < updateColumnCount && defaultUpdates[index];
  }

  public boolean updateIsVarchar(int index) {
    return index >= 0 && index < updateColumnCount && varcharUpdates[index];
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
    return comparison(index) == SqlComparison.EQUAL;
  }

  public boolean isRangePredicate(int index) {
    return comparison(index) == SqlComparison.HALF_OPEN_RANGE;
  }

  public SqlComparison comparison(int index) {
    return index >= 0 && index < predicateCount ? comparisons[index] : null;
  }

  public boolean isLiteralMembership(int index) {
    SqlComparison comparison = comparison(index);
    return comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN;
  }

  public int literalMembershipCount(int index) {
    return isLiteralMembership(index) ? literalMembershipCounts[index] : 0;
  }

  public long literalMembershipValue(int index, int valueIndex) {
    return isLiteralMembership(index)
            && valueIndex >= 0
            && valueIndex < literalMembershipCounts[index]
        ? literalMembershipValues[literalMembershipOffsets[index] + valueIndex]
        : 0;
  }

  public boolean literalMembershipHasNull(int index) {
    return isLiteralMembership(index) && literalMembershipHasNull[index];
  }

  public boolean isNullPredicate(int index) {
    return index >= 0 && index < predicateCount && nullPredicates[index];
  }

  public boolean predicateIsVarchar(int index) {
    return index >= 0 && index < predicateCount && varcharPredicates[index];
  }

  public boolean predicateStartsDisjunction(int index) {
    return index > 0
        && index < predicateCount
        && disjunctionPredicates[index];
  }

  public boolean hasDisjunction() {
    for (int index = 1; index < predicateCount; index++) {
      if (disjunctionPredicates[index]) {
        return true;
      }
    }
    return false;
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

  public boolean hasGroupHaving() {
    return groupHavingComparison != null;
  }

  public SqlComparison groupHavingComparison() {
    return groupHavingComparison;
  }

  public long groupHavingValue() {
    return groupHavingValue;
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

  private static final class ViewQuery implements CharSequence {
    private final char[] characters = new char[MAXIMUM_VIEW_QUERY_LENGTH];
    private int length;

    StatusCode set(CharSequence source, int start, int end) {
      if (source == null
          || start < 0
          || end <= start
          || end > source.length()
          || end - start > characters.length) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int index = start; index < end; index++) {
        char character = source.charAt(index);
        if (character > 0xff) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        characters[index - start] = character;
      }
      length = end - start;
      return StatusCode.OK;
    }

    void reset() {
      length = 0;
    }

    @Override
    public int length() {
      return length;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length) {
        throw new IndexOutOfBoundsException(index);
      }
      return characters[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
