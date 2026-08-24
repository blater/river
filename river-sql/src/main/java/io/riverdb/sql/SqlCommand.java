package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Caller-owned parsed SQL command for the first executable point-statement subset. */
public final class SqlCommand {
  public static final int MAXIMUM_INSERT_ROWS = 64;
  public static final int MAXIMUM_COLUMNS = 8;
  public static final int MAXIMUM_CONSTRAINT_INDEXES = 4;
  public static final int MAXIMUM_PREDICATES = MAXIMUM_COLUMNS;
  public static final int MAXIMUM_VIEW_QUERY_LENGTH = 768;
  public static final int MAXIMUM_TEXT_BYTES = 64 * 1024;

  public static final int UPDATE_LITERAL = 0;
  public static final int UPDATE_EXPRESSION = 1;
  private static final int MUTATION_EXPRESSION_DESCRIPTOR = Integer.MIN_VALUE;

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
  final SqlBooleanPredicateProgram wherePredicates =
      new SqlBooleanPredicateProgram();
  SqlJoinChain joinChain;
  final SqlBooleanPredicateProgram booleanHavingPredicates =
      new SqlBooleanPredicateProgram();
  final SqlIdentifier[] columnNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  final SqlIdentifier[] columnTableNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  final SqlIdentifier[] columnAliases = new SqlIdentifier[MAXIMUM_COLUMNS];
  final SqlIdentifier[] columnReferenceTableNames =
      new SqlIdentifier[MAXIMUM_COLUMNS];
  final SqlIdentifier[] columnReferenceColumnNames =
      new SqlIdentifier[MAXIMUM_COLUMNS];
  final SqlIdentifier orderColumnName = new SqlIdentifier();
  final long[] insertValues =
      new long[MAXIMUM_INSERT_ROWS * MAXIMUM_COLUMNS];
  final long[] insertNullMasks = new long[MAXIMUM_INSERT_ROWS];
  final long[] insertDefaultMasks = new long[MAXIMUM_INSERT_ROWS];
  final int[] insertTypeDescriptors =
      new int[MAXIMUM_INSERT_ROWS * MAXIMUM_COLUMNS];
  final long[] updateValues = new long[MAXIMUM_COLUMNS];
  final long[] columnDefaultValues = new long[MAXIMUM_COLUMNS];
  final byte[] columnDefaultKinds = new byte[MAXIMUM_COLUMNS];
  final int[] columnTypeDescriptors = new int[MAXIMUM_COLUMNS];
  final long[] columnCheckValues = new long[MAXIMUM_COLUMNS];
  final int[] columnCheckTypeDescriptors = new int[MAXIMUM_COLUMNS];
  final SqlComparison[] columnCheckComparisons =
      new SqlComparison[MAXIMUM_COLUMNS];
  final boolean[] nullUpdates = new boolean[MAXIMUM_COLUMNS];
  final boolean[] defaultUpdates = new boolean[MAXIMUM_COLUMNS];
  final int[] updateTypeDescriptors = new int[MAXIMUM_COLUMNS];
  final int[] updateOperators = new int[MAXIMUM_COLUMNS];
  final boolean[] nullProjections = new boolean[MAXIMUM_COLUMNS];
  final byte[] textBytes = new byte[MAXIMUM_TEXT_BYTES];
  SqlCommandType type;
  long key;
  long value;
  long scanLowerInclusive;
  long scanUpperExclusive;
  long columnNotNullMask;
  long columnDefaultMask;
  long columnUniqueMask;
  long columnReferenceMask;
  long rowLimit = Long.MAX_VALUE;
  long sequenceStart = 1;
  long sequenceIncrement = 1;
  boolean boundedScan;
  boolean selectAll;
  boolean readCommittedTransaction;
  boolean serializableTransaction;
  boolean descendingOrder;
  boolean primaryKeyIdentity;
  int insertRowCount;
  int insertColumnCount;
  int updateColumnCount;
  int columnCount;
  boolean available;
  int textBytesUsed;

  public SqlCommand() {
    for (int index = 0; index < columnNames.length; index++) {
      columnNames[index] = new SqlIdentifier();
      columnTableNames[index] = new SqlIdentifier();
      columnAliases[index] = new SqlIdentifier();
      columnReferenceTableNames[index] = new SqlIdentifier();
      columnReferenceColumnNames[index] = new SqlIdentifier();
    }
  }

  public void reset() {
    SqlCommandReset.reset(this);
  }

  void discardJoinChain() {
    if (joinChain != null) {
      joinChain.reset();
      joinChain = null;
    }
  }

  /** Copies one parsed query block into statement-owned execution scratch. */
  public StatusCode copyBlockFrom(SqlCommand source) {
    if (source == null || !source.isAvailable()) {
      reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    copyQueryFrom(source);
    return finish();
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

  StatusCode expandSelectAllFrom(SqlCommand source) {
    return SqlCommandQueryState.expandSelectAll(this, source);
  }

  void setRowLimit(long maximumRows) {
    rowLimit = maximumRows;
  }

  void copyQueryFrom(SqlCommand source) {
    SqlCommandQueryState.copy(this, source);
  }

  void setBegin(boolean readCommitted, boolean serializable) {
    type = SqlCommandType.BEGIN;
    readCommittedTransaction = readCommitted;
    serializableTransaction = serializable;
  }

  void appendInsert(
      long[] values,
      long nullMask,
      long defaultMask,
      int[] typeDescriptors,
      int count) {
    SqlCommandInsertView.append(this, values, nullMask, defaultMask, typeDescriptors, count);
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
      long updateValue,
      boolean isNull,
      boolean isDefault,
      int typeDescriptor,
      int operator) {
    SqlCommandUpdateView.append(
        this, updateValue, isNull, isDefault, typeDescriptor, operator);
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

  SqlJoinChain beginJoinChain() {
    SqlJoinChain joins = writableJoinChain();
    joins.begin(tableName, tableAlias);
    tableName.reset();
    tableAlias.reset();
    return joins;
  }

  SqlJoinChain writableJoinChain() {
    if (joinChain == null) joinChain = new SqlJoinChain();
    return joinChain;
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
    if (columnCount >= columnNames.length) {
      return null;
    }
    columnTypeDescriptors[columnCount] = SqlTypeDescriptor.BIGINT;
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

  StatusCode setProjectionNull(int index) {
    return SqlCommandProjectionView.setNull(this, index);
  }

  void markLastColumnNotNull() {
    SqlCommandColumnConstraints.markNotNull(this);
  }

  void markPrimaryKeyIdentity() {
    SqlCommandColumnConstraints.markIdentity(this);
  }

  void markLastColumnDefault(long value) {
    SqlCommandColumnConstraints.markDefault(this, value);
  }

  void markLastColumnCurrentDefault(int kind) {
    SqlCommandColumnConstraints.markCurrentDefault(this, kind);
  }

  void markLastColumnVarchar(int maximumScalars) {
    SqlCommandColumnConstraints.markVarchar(this, maximumScalars);
  }

  void markLastColumnType(int descriptor) {
    SqlCommandColumnConstraints.markType(this, descriptor);
  }

  StatusCode markLastColumnUnique() {
    return SqlCommandColumnConstraints.markUnique(this);
  }

  SqlIdentifier writableLastColumnReferenceTableName() {
    return SqlCommandColumnConstraints.referenceTable(this);
  }

  SqlIdentifier writableLastColumnReferenceColumnName() {
    return SqlCommandColumnConstraints.referenceColumn(this);
  }

  StatusCode markLastColumnReference() {
    return SqlCommandColumnConstraints.markReference(this);
  }

  void markLastColumnCheck(SqlComparison comparison, long value, int descriptor) {
    SqlCommandColumnConstraints.markCheck(this, comparison, value, descriptor);
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
    return orderColumnName;
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

  public SqlIdentifier predicateSymbolTable(int index) {
    return SqlCommandProjectionView.symbolTable(this, index);
  }

  public SqlIdentifier predicateSymbolName(int index) {
    return SqlCommandProjectionView.symbolName(this, index);
  }

  public int projectionSymbolCount() {
    return SqlCommandProjectionView.symbolCount(this);
  }

  public SqlIdentifier projectionSymbolTable(int index) {
    return SqlCommandProjectionView.symbolTable(this, index);
  }

  public SqlIdentifier projectionSymbolName(int index) {
    return SqlCommandProjectionView.symbolName(this, index);
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

  public int columnDefaultKind(int index) {
    return columnHasDefault(index)
        ? Byte.toUnsignedInt(columnDefaultKinds[index]) : 0;
  }

  public boolean columnIsVarchar(int index) {
    return index > 0
        && index < columnCount
        && SqlTypeDescriptor.typeId(columnTypeDescriptors[index])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  public int columnTypeDescriptor(int index) {
    return index >= 0 && index < columnCount ? columnTypeDescriptors[index] : 0;
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

  public int columnCheckTypeDescriptor(int index) {
    return columnHasCheck(index) ? columnCheckTypeDescriptors[index] : 0;
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
        ? SqlCommandUpdateView.value(this, 0) : value;
  }

  public int updateColumnCount() {
    return updateColumnCount;
  }

  public long updateValue(int index) {
    return SqlCommandUpdateView.value(this, index);
  }

  public boolean updateHasExpression(int index) {
    return updateOperator(index) == UPDATE_EXPRESSION;
  }

  public int updateExpression(int index) {
    return updateHasExpression(index) ? (int) updateValue(index) : -1;
  }

  public int mutationExpressionCount() {
    return SqlCommandExpressionView.mutationCount(this);
  }

  public int mutationExpressionNodeCount(int expression) {
    return SqlCommandExpressionView.mutationNodes(this, expression);
  }

  public int mutationExpressionOperator(int expression, int node) {
    return SqlCommandExpressionView.mutationOperator(this, expression, node);
  }

  public long mutationExpressionOperand(int expression, int node) {
    return SqlCommandExpressionView.mutationOperand(this, expression, node);
  }

  public int mutationExpressionTypeDescriptor(int expression, int node) {
    return SqlCommandExpressionView.mutationDescriptor(this, expression, node);
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

  public int insertExpression(int rowIndex, int columnIndex) {
    return insertHasExpression(rowIndex, columnIndex)
        ? (int) insertValue(rowIndex, columnIndex) : -1;
  }

  static int mutationExpressionDescriptor() {
    return MUTATION_EXPRESSION_DESCRIPTOR;
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
    return wherePredicates.isAvailable();
  }

  public boolean isSelectAll() {
    return selectAll;
  }

  int appendAggregateInvocation(int kind, int operandProjection) {
    return aggregates.appendInvocation(kind, operandProjection);
  }

  boolean appendAggregateOutput(int invocation) {
    return aggregates.appendOutput(invocation);
  }

  public int aggregateInvocationCount() {
    return SqlCommandExpressionView.aggregateCount(this);
  }

  public int aggregateOutputCount() {
    return SqlCommandExpressionView.aggregateOutputs(this);
  }

  public int aggregateKind(int invocation) {
    return SqlCommandExpressionView.aggregateKind(this, invocation);
  }

  public int aggregateOperandProjection(int invocation) {
    return SqlCommandExpressionView.aggregateOperand(this, invocation);
  }

  public int aggregateOutputInvocation(int output) {
    return SqlCommandExpressionView.aggregateOutput(this, output);
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
