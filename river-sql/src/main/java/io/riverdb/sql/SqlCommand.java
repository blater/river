package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Caller-owned parsed SQL command for the first executable point-statement subset. */
public final class SqlCommand {
  private final SqlJoinAllocator joinAllocator;
  public static final int MAXIMUM_INSERT_ROWS = 64;
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
  final SqlTableConstraintSet tableConstraints = new SqlTableConstraintSet();
  final SqlBooleanPredicateProgram wherePredicates =
      new SqlBooleanPredicateProgram();
  SqlJoinChain joinChain;
  final SqlBooleanPredicateProgram booleanHavingPredicates =
      new SqlBooleanPredicateProgram();
  SqlIdentifier[] columnNames = new SqlIdentifier[8];
  SqlIdentifier[] columnTableNames = new SqlIdentifier[8];
  SqlIdentifier[] columnAliases = new SqlIdentifier[8];
  SqlIdentifier[] columnReferenceTableNames = new SqlIdentifier[8];
  SqlIdentifier[] columnReferenceColumnNames = new SqlIdentifier[8];
  final SqlOrderByList orderBy = new SqlOrderByList();
  final SqlInsertRows inserts = new SqlInsertRows();
  long[] updateHighs = new long[8];
  long[] updateValues = new long[8];
  long[] columnDefaultHighs = new long[8];
  long[] columnDefaultValues = new long[8];
  byte[] columnDefaultKinds = new byte[8];
  int[] columnTypeDescriptors = new int[8];
  long[] columnCheckHighs = new long[8];
  long[] columnCheckValues = new long[8];
  int[] columnCheckTypeDescriptors = new int[8];
  SqlComparison[] columnCheckComparisons = new SqlComparison[8];
  boolean[] nullUpdates = new boolean[8];
  boolean[] defaultUpdates = new boolean[8];
  int[] updateTypeDescriptors = new int[8];
  int[] updateOperators = new int[8];
  boolean[] nullProjections = new boolean[8];
  boolean[] columnNotNull = new boolean[8];
  boolean[] columnDefaults = new boolean[8];
  boolean[] columnUnique = new boolean[8];
  boolean[] columnReferences = new boolean[8];
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
  boolean primaryKeyIdentity;
  int primaryKeyIdentityColumn = -1;
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
      columnReferenceTableNames[index] = new SqlIdentifier();
      columnReferenceColumnNames[index] = new SqlIdentifier();
    }
  }

  public void reset() {
    SqlCommandReset.reset(this);
  }

  void discardJoinChain() {
    if (joinChain != null) joinChain.reset();
    joinChain = null;
  }

  StatusCode lowerJoinAggregateSource(SqlCommand root) {
    for (int invocation = 0; invocation < aggregates.invocationCount(); invocation++) {
      if (aggregates.operandProjection(invocation) >= 0) continue;
      int output = aggregateOutputProjection(invocation);
      if (output >= 0) {
        projections.expression(output).replaceWithLiteral(1, SqlTypeDescriptor.BIGINT);
      }
    }
    for (int group = 0; group < grouping.count(); group++) {
      int projection = grouping.projection(group);
      if (projection < 0) {
        projection = columnCount;
        SqlIdentifier column = writableNextColumnName();
        if (column == null) return StatusCode.RESOURCE_EXHAUSTED;
        SqlScalarExpression destination = projections.expression(projection);
        StatusCode status = destination.copyFrom(grouping.expression(group));
        if (!status.isOk()) return status;
        int symbol = destination.isDirectColumnReference()
            ? (int) destination.operand(0) : -1;
        SqlIdentifier name = symbol < 0 ? null : projections.symbolName(symbol);
        if (name != null) column.copyFrom(name);
      }
      root.grouping.setOperandProjection(group, projection);
    }
    aggregates.reset();
    grouping.reset();
    booleanHavingPredicates.reset();
    orderBy.reset();
    descendingOrder = false;
    rowLimit = Long.MAX_VALUE;
    type = SqlCommandType.JOIN_SCAN;
    return StatusCode.OK;
  }

  private int aggregateOutputProjection(int invocation) {
    int groups = columnCount - aggregates.outputCount();
    for (int output = 0; output < aggregates.outputCount(); output++) {
      if (aggregates.outputInvocation(output) == invocation) return groups + output;
    }
    return -1;
  }

  void lowerJoinAggregateRoot() {
    wherePredicates.reset();
    if (joinChain != null) joinChain.clearPredicates();
    type = SqlAggregateCommandType.route(
        aggregateKind(0), groupExpressionCount() > 0);
  }

  /** Copies one parsed query block into statement-owned execution scratch. */
  public StatusCode copyBlockFrom(SqlCommand source) {
    if (source == null || !source.isAvailable()) {
      reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = copyQueryFrom(source);
    return status.isOk() ? finish() : status;
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
    markLastColumnDefault(value >> 63, value);
  }

  void markLastColumnDefault(long high, long value) {
    SqlCommandColumnConstraints.markDefault(this, high, value);
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

  StatusCode beginTableConstraint(int kind) { return tableConstraints.begin(kind); }
  StatusCode addTableConstraintPart(CharSequence part, CharSequence target) {
    return tableConstraints.addPart(part, target);
  }
  SqlIdentifier writableTableConstraintName() { return tableConstraints.name(); }
  SqlIdentifier writableTableConstraintReferenceTable() { return tableConstraints.table(); }

  SqlIdentifier writableLastColumnReferenceTableName() {
    return SqlCommandColumnConstraints.referenceTable(this);
  }

  SqlIdentifier writableLastColumnReferenceColumnName() {
    return SqlCommandColumnConstraints.referenceColumn(this);
  }

  StatusCode markLastColumnReference() {
    return SqlCommandColumnConstraints.markReference(this);
  }

  void markLastColumnCheck(
      SqlComparison comparison, long high, long value, int descriptor) {
    SqlCommandColumnConstraints.markCheck(this, comparison, high, value, descriptor);
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

  SqlIdentifier writableNextOrderColumnName() { return orderBy.append(); }

  SqlIdentifier writableOrderColumnTableName(int expression) {
    return orderBy.qualifier(expression);
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
        && columnNotNull[index];
  }

  public boolean columnHasDefault(int index) {
    return index >= 0
        && index < columnCount
        && columnDefaults[index];
  }

  public long columnDefaultValue(int index) {
    return columnHasDefault(index) ? columnDefaultValues[index] : 0;
  }

  public long columnDefaultHigh(int index) {
    return columnHasDefault(index) ? columnDefaultHighs[index] : 0;
  }

  public int columnDefaultKind(int index) {
    return columnHasDefault(index)
        ? Byte.toUnsignedInt(columnDefaultKinds[index]) : 0;
  }

  public boolean columnIsVarchar(int index) {
    return index >= 0
        && index < columnCount
        && SqlTypeDescriptor.typeId(columnTypeDescriptors[index])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  public int columnTypeDescriptor(int index) {
    return index >= 0 && index < columnCount ? columnTypeDescriptors[index] : 0;
  }

  public boolean columnIsUnique(int index) {
    return index >= 0
        && index < columnCount
        && columnUnique[index];
  }

  public boolean hasUniqueColumns() {
    return SqlCommandColumnConstraints.any(columnUnique, columnCount);
  }

  public boolean columnHasReference(int index) {
    return index >= 0
        && index < columnCount
        && columnReferences[index];
  }

  public SqlIdentifier columnReferenceTableName(int index) {
    return columnHasReference(index) ? columnReferenceTableNames[index] : null;
  }

  public SqlIdentifier columnReferenceColumnName(int index) {
    return columnHasReference(index) ? columnReferenceColumnNames[index] : null;
  }

  public boolean hasReferences() {
    return SqlCommandColumnConstraints.any(columnReferences, columnCount);
  }

  public boolean hasPrimaryKeyIdentity() {
    return primaryKeyIdentity;
  }

  int primaryKeyIdentityColumn() {
    return primaryKeyIdentityColumn;
  }

  void markColumnNotNull(int index) {
    if (index >= 0 && index < columnCount) columnNotNull[index] = true;
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

  public long columnCheckHigh(int index) {
    return columnHasCheck(index) ? columnCheckHighs[index] : 0;
  }

  public int columnCheckTypeDescriptor(int index) {
    return columnHasCheck(index) ? columnCheckTypeDescriptors[index] : 0;
  }

  public int tableConstraintCount() { return tableConstraints.count(); }
  public int tableConstraintKind(int index) { return tableConstraints.kind(index); }
  public SqlIdentifier tableConstraintName(int index) { return tableConstraints.name(index); }
  public int tableConstraintPartCount(int index) { return tableConstraints.partCount(index); }
  public SqlIdentifier tableConstraintPartName(int index, int part) {
    return tableConstraints.part(index, part);
  }
  public SqlIdentifier tableConstraintReferenceTableName(int index) {
    return tableConstraints.table(index);
  }
  public SqlIdentifier tableConstraintReferencePartName(int index, int part) {
    return tableConstraints.target(index, part);
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
    return orderBy.name(0);
  }

  public int orderExpressionCount() { return orderBy.count(); }
  public SqlIdentifier orderColumnName(int expression) { return orderBy.name(expression); }
  public SqlIdentifier orderColumnTableName(int expression) {
    return orderBy.qualifier(expression);
  }


  public boolean isOrdered() {
    return orderBy.count() > 0;
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

  public boolean isDescendingOrder(int expression) {
    return orderBy.descending(expression);
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

  public long mutationExpressionOperandHigh(int expression, int node) {
    return SqlCommandExpressionView.mutationOperandHigh(this, expression, node);
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

  StatusCode appendGroupExpression(int projection, SqlScalarExpression expression) {
    return grouping.append(projection, expression);
  }

  public int groupExpressionCount() { return grouping.count(); }
  public int groupProjection(int expression) { return grouping.projection(expression); }
  public int groupOperandProjection(int expression) {
    return grouping.operandProjection(expression);
  }
  public SqlScalarExpression groupExpression(int expression) {
    return grouping.expression(expression);
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
