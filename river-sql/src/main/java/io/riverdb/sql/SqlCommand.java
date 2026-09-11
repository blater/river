package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;

/** Caller-owned parsed SQL command for the first executable point-statement subset. */
public final class SqlCommand {
  private final SqlJoinAllocator joinAllocator;
  /** Optional batching granule for fixtures and callers; parsing does not enforce it. */
  public static final int RECOMMENDED_INSERT_BATCH_ROWS = 64;
  public static final int MAXIMUM_COLUMNS = SqlShapeLimits.MAX_TABLE_COLUMNS;
  public static final int MAXIMUM_PROJECTIONS = SqlShapeLimits.MAX_RESULT_COLUMNS;
  public static final int MAXIMUM_CONSTRAINT_INDEXES =
      SqlShapeLimits.MAX_SECONDARY_INDEXES + SqlShapeLimits.MAX_FOREIGN_KEYS;
  public static final int MAXIMUM_PREDICATES = SqlShapeLimits.MAX_PREDICATE_LEAVES;
  public static final int MAXIMUM_VIEW_QUERY_LENGTH = 768;
  public static final int MAXIMUM_TEXT_BYTES = 64 * 1024;
  public static final int CONSTRAINT_PRIMARY_KEY = SqlTableConstraintSet.PRIMARY;
  public static final int CONSTRAINT_UNIQUE = SqlTableConstraintSet.UNIQUE;
  public static final int CONSTRAINT_FOREIGN_KEY = SqlTableConstraintSet.FOREIGN;
  public static final int CONSTRAINT_CHECK = SqlTableConstraintSet.CHECK;

  public static final int UPDATE_LITERAL = 0;
  public static final int UPDATE_EXPRESSION = 1;
  static final int UPDATE_PARAMETER = 2;
  private static final int MUTATION_EXPRESSION_DESCRIPTOR = Integer.MIN_VALUE;
  private static final int MUTATION_PARAMETER_DESCRIPTOR = Integer.MIN_VALUE + 1;

  static final long INVALID_TEXT_HANDLE = Long.MIN_VALUE;

  final SqlIdentifier tableName = new SqlIdentifier();
  final SqlIdentifier renamedTableName = new SqlIdentifier();
  final SqlIdentifier tableAlias = new SqlIdentifier();
  final SqlIdentifier indexName = new SqlIdentifier();
  final SqlIdentifier renamedIndexName = new SqlIdentifier();
  final SqlIdentifier sequenceName = new SqlIdentifier();
  final SqlIdentifier savepointName = new SqlIdentifier();
  final SqlViewQuery viewQuery = new SqlViewQuery();
  final SqlScalarExpression scalarExpression = new SqlScalarExpression();
  final SqlProjectionList projections = new SqlProjectionList();
  final SqlMutationExpressions mutationExpressions =
      new SqlMutationExpressions();
  final SqlAggregateSet aggregates = new SqlAggregateSet();
  final SqlGroupingList grouping = new SqlGroupingList();
  final SqlCommandColumnConstraints columnConstraints = new SqlCommandColumnConstraints();
  final SqlTableConstraintSet tableConstraints = new SqlTableConstraintSet();
  final SqlBooleanPredicateProgram wherePredicates =
      new SqlBooleanPredicateProgram();
  SqlJoinChain joinChain;
  final SqlBooleanPredicateProgram booleanHavingPredicates =
      new SqlBooleanPredicateProgram();
  SqlIdentifier[] columnNames = new SqlIdentifier[8];
  SqlIdentifier[] columnTableNames = new SqlIdentifier[8];
  SqlIdentifier[] columnAliases = new SqlIdentifier[8];
  final SqlOrderByList orderBy = new SqlOrderByList();
  final SqlInsertRows inserts = new SqlInsertRows();
  long[] updateHighs = new long[8];
  long[] updateValues = new long[8];
  boolean[] nullUpdates = new boolean[8];
  boolean[] defaultUpdates = new boolean[8];
  int[] updateTypeDescriptors = new int[8];
  int[] updateOperators = new int[8];
  boolean[] nullProjections = new boolean[8];
  final byte[] textBytes = new byte[MAXIMUM_TEXT_BYTES];
  SqlCommandType type;
  long key;
  long value;
  long scanLowerInclusive;
  long scanUpperExclusive;
  long rowLimit = Long.MAX_VALUE;
  long sequenceStart = 1;
  long sequenceIncrement = 1;
  boolean boundedScan;
  boolean selectAll;
  boolean selectForUpdate;
  boolean readCommittedTransaction;
  boolean serializableTransaction;
  boolean descendingOrder;
  int insertRowCount;
  int insertColumnCount;
  int updateColumnCount;
  int columnCount;
  boolean available;
  int textBytesUsed;

  public SqlCommand() { this(SqlJoinAllocator.STANDARD); }

  SqlCommand(SqlJoinAllocator allocator) {
    joinAllocator = allocator;
    for (int index = 0; index < columnNames.length; index++) {
      columnNames[index] = new SqlIdentifier();
      columnTableNames[index] = new SqlIdentifier();
      columnAliases[index] = new SqlIdentifier();
    }
  }

  /** Borrowed parsed metadata; copy retained values before command reuse. */
  public SqlMutationExpressions mutationExpressions() { return mutationExpressions; }
  public SqlAggregateSet aggregates() { return aggregates; }
  public SqlGroupingList grouping() { return grouping; }
  public SqlOrderByList orderBy() { return orderBy; }
  public SqlTableConstraintSet tableConstraints() { return tableConstraints; }
  public SqlProjectionList projections() { return projections; }

  public void reset() {
    SqlCommandReset.reset(this);
  }

  void discardJoinChain() {
    if (joinChain != null) joinChain.reset();
    joinChain = null;
  }

  /** Copies one parsed query block into statement-owned execution scratch. */
  public StatusCode copyBlockFrom(SqlCommand source) {
    return SqlCommandQueryState.copyBlock(this, source);
  }

  void set(SqlCommandType commandType, long primaryKey, long rowValue) {
    type = commandType;
    key = primaryKey;
    value = rowValue;
  }

  void setScan(long lowerInclusive, long upperExclusive, boolean bounded) {
    type = SqlCommandType.SCAN;
    scanLowerInclusive = lowerInclusive;
    scanUpperExclusive = upperExclusive;
    boundedScan = bounded;
  }

  void setSelectAll() {
    selectAll = true;
  }

  void setSelectForUpdate() {
    selectForUpdate = true;
  }

  StatusCode expandSelectAllFrom(SqlCommand source) {
    return SqlCommandQueryState.expandSelectAll(this, source);
  }

  void setRowLimit(long maximumRows) {
    rowLimit = maximumRows;
  }

  StatusCode copyQueryFrom(SqlCommand source) {
    return SqlCommandQueryState.copy(this, source);
  }

  void setBegin(boolean readCommitted, boolean serializable) {
    type = SqlCommandType.BEGIN;
    readCommittedTransaction = readCommitted;
    serializableTransaction = serializable;
  }

  boolean appendInsert(
      long[] highs,
      long[] values,
      boolean[] nulls,
      boolean[] defaults,
      int[] typeDescriptors,
      int count) {
    return SqlCommandInsertView.append(
        this, highs, values, nulls, defaults, typeDescriptors, count);
  }

  void setInsert() {
    SqlCommandInsertView.setInsert(this);
  }

  StatusCode finish() {
    boolean wasAvailable = available;
    available = false;
    if (wasAvailable || type == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    available = true;
    return StatusCode.OK;
  }

  void appendUpdate(
      long updateHigh,
      long updateValue,
      boolean isNull,
      boolean isDefault,
      int typeDescriptor,
      int operator) {
    SqlCommandUpdateView.append(
        this, updateHigh, updateValue, isNull, isDefault, typeDescriptor, operator);
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

  StatusCode beginJoinChain() {
    StatusCode status = ensureJoinChain();
    if (!status.isOk()) return status;
    SqlJoinChain joins = joinChain;
    joins.begin(tableName, tableAlias);
    tableName.reset();
    tableAlias.reset();
    return StatusCode.OK;
  }

  SqlJoinChain writableJoinChain() {
    return joinChain;
  }

  StatusCode ensureJoinChain() {
    if (joinChain != null) return StatusCode.OK;
    try {
      SqlJoinChain next = joinAllocator.chain();
      joinChain = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
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
    int maximum = SqlCommandCapacity.maximumColumns(type);
    if (columnCount >= maximum || !SqlCommandCapacity.ensureColumns(this, columnCount + 1)) {
      return null;
    }
    columnConstraints.initializeColumn(columnCount);
    return columnNames[columnCount++];
  }

  SqlScalarExpression writableProjectionExpression(int index) {
    return SqlCommandProjectionView.expression(this, index);
  }

  int appendMutationExpression(SqlScalarExpression expression) {
    return mutationExpressions.append(expression);
  }

  int registerProjectionSymbol(CharSequence table, CharSequence name) {
    return SqlCommandProjectionView.register(this, table, name);
  }

  SqlBooleanPredicateProgram writableWherePredicates() {
    return wherePredicates;
  }

  SqlBooleanPredicateProgram writableBooleanHavingPredicates() {
    return booleanHavingPredicates;
  }

  int registerPredicateSymbol(CharSequence table, CharSequence name) {
    return SqlCommandProjectionView.register(this, table, name);
  }

  void adoptDirectProjectionName(int index) {
    SqlCommandProjectionView.adoptName(this, index);
  }

  StatusCode setProjectionColumn(
      int index, CharSequence table, CharSequence name) {
    return SqlCommandProjectionView.setColumn(this, index, table, name);
  }

  void markLastColumnNotNull() {
    columnConstraints.markNotNull(columnCount);
  }

  void markPrimaryKeyIdentity() {
    columnConstraints.markIdentity(columnCount);
  }

  void markLastColumnDefault(long value) {
    markLastColumnDefault(value >> 63, value);
  }

  void markLastColumnDefault(long high, long value) {
    columnConstraints.markDefault(columnCount, high, value);
  }

  void markLastColumnCurrentDefault(int kind) {
    columnConstraints.markCurrentDefault(columnCount, kind);
  }

  void markLastColumnType(int descriptor) {
    columnConstraints.markType(columnCount, descriptor);
  }

  StatusCode markLastColumnUnique() {
    return columnConstraints.markUnique(columnCount);
  }

  SqlIdentifier writableLastColumnReferenceTableName() {
    return columnConstraints.referenceTable(columnCount);
  }

  SqlIdentifier writableLastColumnReferenceColumnName() {
    return columnConstraints.referenceColumn(columnCount);
  }

  StatusCode markLastColumnReference() {
    return columnConstraints.markReference(columnCount);
  }

  void markLastColumnCheck(
      SqlComparison comparison, long high, long value, int descriptor) {
    columnConstraints.markCheck(columnCount, comparison, high, value, descriptor);
  }

  long storeText(char[] source, int offset, int length) {
    return SqlCommandTextStore.store(this, source, offset, length);
  }

  long copyTextFrom(SqlCommand source, long handle) {
    return SqlCommandTextStore.copyFrom(this, source, handle);
  }

  long textSuccessor(long handle) {
    return SqlCommandTextStore.successor(this, handle);
  }

  int compareText(long left, long right) {
    return SqlCommandTextStore.compare(this, left, right);
  }

  public int textByteLength(long handle) {
    return SqlCommandTextStore.length(this, handle);
  }

  public int copyText(long handle, ByteBuffer target) {
    return SqlCommandTextStore.copy(this, handle, target);
  }

  public byte textByteAt(long handle, int index) {
    return SqlCommandTextStore.byteAt(this, handle, index);
  }

  void markLastProjectionNull() {
    SqlCommandProjectionView.markNull(this);
  }

  SqlIdentifier writableColumnTableName(int index) {
    return index >= 0 && index < columnCount ? columnTableNames[index] : null;
  }

  SqlIdentifier writableColumnAlias(int index) {
    return index >= 0 && index < columnCount ? columnAliases[index] : null;
  }

  SqlIdentifier writableOrderColumnName() {
    SqlIdentifier current = orderBy.name(0);
    return current == null ? orderBy.append() : current;
  }

  public SqlCommandType type() {
    return type;
  }

  public SqlIdentifier tableName() {
    return joinChain == null || joinChain.roleCount() == 0
        ? tableName : joinChain.tableName(0);
  }

  public SqlIdentifier renamedTableName() {
    return renamedTableName;
  }

  public SqlIdentifier tableAlias() {
    return joinChain == null || joinChain.roleCount() == 0
        ? tableAlias : joinChain.alias(0);
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

  public SqlScalarExpression scalarExpression() {
    return scalarExpression;
  }

  public SqlScalarExpression projectionExpression(int index) {
    return SqlCommandProjectionView.expression(this, index);
  }

  /** Returns a physical aggregate operand lane, including hidden HAVING operands. */
  public SqlScalarExpression aggregateOperandExpression(int index) {
    return SqlCommandProjectionView.aggregateExpression(this, index);
  }

  public SqlBooleanPredicateProgram wherePredicates() {
    return wherePredicates;
  }

  public SqlJoinChain joinChain() {
    return joinChain != null && joinChain.stageCount() > 0 ? joinChain : null;
  }

  public SqlBooleanPredicateProgram booleanHavingPredicates() {
    return booleanHavingPredicates;
  }

  public int directProjectionSymbol(int index) {
    return SqlCommandProjectionView.directSymbol(this, index);
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
    return columnConstraints.columnIsNotNull(columnCount, index);
  }

  public boolean columnHasDefault(int index) {
    return columnConstraints.columnHasDefault(columnCount, index);
  }

  public long columnDefaultValue(int index) {
    return columnConstraints.columnDefaultValue(columnCount, index);
  }

  public long columnDefaultHigh(int index) {
    return columnConstraints.columnDefaultHigh(columnCount, index);
  }

  public int columnDefaultKind(int index) {
    return columnConstraints.columnDefaultKind(columnCount, index);
  }

  public boolean columnIsVarchar(int index) {
    return columnConstraints.columnIsVarchar(columnCount, index);
  }

  public int columnTypeDescriptor(int index) {
    return columnConstraints.columnTypeDescriptor(columnCount, index);
  }

  public boolean columnIsUnique(int index) {
    return columnConstraints.columnIsUnique(columnCount, index);
  }

  public boolean hasUniqueColumns() {
    return columnConstraints.hasUniqueColumns(columnCount);
  }

  public boolean columnHasReference(int index) {
    return columnConstraints.columnHasReference(columnCount, index);
  }

  public SqlIdentifier columnReferenceTableName(int index) {
    return columnConstraints.columnReferenceTableName(columnCount, index);
  }

  public SqlIdentifier columnReferenceColumnName(int index) {
    return columnConstraints.columnReferenceColumnName(columnCount, index);
  }

  public boolean hasReferences() {
    return columnConstraints.hasReferences(columnCount);
  }

  public boolean hasPrimaryKeyIdentity() {
    return columnConstraints.hasPrimaryKeyIdentity();
  }

  int primaryKeyIdentityColumn() {
    return columnConstraints.primaryKeyIdentityColumn();
  }

  void markColumnNotNull(int index) {
    columnConstraints.markNotNull(columnCount, index);
  }

  public boolean columnHasCheck(int index) {
    return columnConstraints.columnHasCheck(columnCount, index);
  }

  public SqlComparison columnCheckComparison(int index) {
    return columnConstraints.columnCheckComparison(columnCount, index);
  }

  public long columnCheckValue(int index) {
    return columnConstraints.columnCheckValue(columnCount, index);
  }

  public long columnCheckHigh(int index) {
    return columnConstraints.columnCheckHigh(columnCount, index);
  }

  public int columnCheckTypeDescriptor(int index) {
    return columnConstraints.columnCheckTypeDescriptor(columnCount, index);
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
    return SqlCommandProjectionView.isNull(this, index);
  }

  void setDescendingOrder(boolean descending) {
    descendingOrder = descending;
    orderBy.descending(0, descending);
  }

  public boolean isDescendingOrder() {
    return orderBy.count() == 0 ? descendingOrder : orderBy.descending(0);
  }

  /** Whether selected base rows must be exclusively protected through transaction completion. */
  public boolean isSelectForUpdate() {
    return selectForUpdate;
  }

  void setDescendingOrder(int expression, boolean descending) {
    orderBy.descending(expression, descending);
    if (expression == 0) descendingOrder = descending;
  }

  public long key() {
    return key;
  }

  public long value() {
    return type == SqlCommandType.UPDATE && updateColumnCount > 0
        ? SqlCommandUpdateView.value(this, 0) : value;
  }

  public int updateColumnCount() {
    return updateColumnCount;
  }

  public long updateValue(int index) {
    return SqlCommandUpdateView.value(this, index);
  }

  public long updateValueHigh(int index) {
    return SqlCommandUpdateView.high(this, index);
  }

  public boolean updateHasExpression(int index) {
    return updateOperator(index) == UPDATE_EXPRESSION;
  }

  public boolean updateHasParameter(int index) {
    return updateOperator(index) == UPDATE_PARAMETER;
  }

  public int updateExpression(int index) {
    return updateHasExpression(index) ? (int) updateValue(index) : -1;
  }

  public int updateOperator(int index) {
    return SqlCommandUpdateView.operator(this, index);
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
    return SqlCommandInsertView.value(this, rowIndex, columnIndex);
  }

  public long insertValueHigh(int rowIndex, int columnIndex) {
    return SqlCommandInsertView.high(this, rowIndex, columnIndex);
  }

  public boolean insertIsNull(int rowIndex, int columnIndex) {
    return SqlCommandInsertView.isNull(this, rowIndex, columnIndex);
  }

  public boolean insertIsDefault(int rowIndex, int columnIndex) {
    return SqlCommandInsertView.isDefault(this, rowIndex, columnIndex);
  }

  public int insertTypeDescriptor(int rowIndex, int columnIndex) {
    return SqlCommandInsertView.typeDescriptor(this, rowIndex, columnIndex);
  }

  public boolean insertHasExpression(int rowIndex, int columnIndex) {
    return insertTypeDescriptor(rowIndex, columnIndex)
        == MUTATION_EXPRESSION_DESCRIPTOR;
  }

  public boolean insertHasParameter(int rowIndex, int columnIndex) {
    return insertTypeDescriptor(rowIndex, columnIndex)
        == MUTATION_PARAMETER_DESCRIPTOR;
  }

  public int insertExpression(int rowIndex, int columnIndex) {
    return insertHasExpression(rowIndex, columnIndex)
        ? (int) insertValue(rowIndex, columnIndex) : -1;
  }

  static int mutationExpressionDescriptor() {
    return MUTATION_EXPRESSION_DESCRIPTOR;
  }

  static int mutationParameterDescriptor() {
    return MUTATION_PARAMETER_DESCRIPTOR;
  }

  public boolean updateIsNull(int index) {
    return SqlCommandUpdateView.isNull(this, index);
  }

  public boolean updateIsDefault(int index) {
    return SqlCommandUpdateView.isDefault(this, index);
  }

  public int updateTypeDescriptor(int index) {
    return SqlCommandUpdateView.typeDescriptor(this, index);
  }

  public boolean isBoundedScan() {
    return boundedScan;
  }

  public boolean hasPredicate() {
    return wherePredicates.isAvailable();
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
