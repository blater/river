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

  private final SqlIdentifier tableName = new SqlIdentifier();
  private final SqlIdentifier renamedTableName = new SqlIdentifier();
  private final SqlIdentifier tableAlias = new SqlIdentifier();
  private final SqlIdentifier indexName = new SqlIdentifier();
  private final SqlIdentifier renamedIndexName = new SqlIdentifier();
  private final SqlIdentifier sequenceName = new SqlIdentifier();
  private final SqlIdentifier savepointName = new SqlIdentifier();
  private final ViewQuery viewQuery = new ViewQuery();
  private final SqlScalarExpression scalarExpression = new SqlScalarExpression();
  private final SqlProjectionList projections = new SqlProjectionList();
  private final SqlMutationExpressions mutationExpressions =
      new SqlMutationExpressions();
  private final SqlAggregateSet aggregates = new SqlAggregateSet();
  private final SqlBooleanPredicateProgram wherePredicates =
      new SqlBooleanPredicateProgram();
  private SqlJoinChain joinChain;
  private final SqlBooleanPredicateProgram booleanHavingPredicates =
      new SqlBooleanPredicateProgram();
  private final SqlIdentifier[] columnNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnTableNames = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnAliases = new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnReferenceTableNames =
      new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier[] columnReferenceColumnNames =
      new SqlIdentifier[MAXIMUM_COLUMNS];
  private final SqlIdentifier orderColumnName = new SqlIdentifier();
  private final long[] insertValues =
      new long[MAXIMUM_INSERT_ROWS * MAXIMUM_COLUMNS];
  private final long[] insertNullMasks = new long[MAXIMUM_INSERT_ROWS];
  private final long[] insertDefaultMasks = new long[MAXIMUM_INSERT_ROWS];
  private final int[] insertTypeDescriptors =
      new int[MAXIMUM_INSERT_ROWS * MAXIMUM_COLUMNS];
  private final long[] updateValues = new long[MAXIMUM_COLUMNS];
  private final long[] columnDefaultValues = new long[MAXIMUM_COLUMNS];
  private final byte[] columnDefaultKinds = new byte[MAXIMUM_COLUMNS];
  private final int[] columnTypeDescriptors = new int[MAXIMUM_COLUMNS];
  private final long[] columnCheckValues = new long[MAXIMUM_COLUMNS];
  private final int[] columnCheckTypeDescriptors = new int[MAXIMUM_COLUMNS];
  private final SqlComparison[] columnCheckComparisons =
      new SqlComparison[MAXIMUM_COLUMNS];
  private final boolean[] nullUpdates = new boolean[MAXIMUM_COLUMNS];
  private final boolean[] defaultUpdates = new boolean[MAXIMUM_COLUMNS];
  private final int[] updateTypeDescriptors = new int[MAXIMUM_COLUMNS];
  private final int[] updateOperators = new int[MAXIMUM_COLUMNS];
  private final boolean[] nullProjections = new boolean[MAXIMUM_COLUMNS];
  private final byte[] textBytes = new byte[MAXIMUM_TEXT_BYTES];
  private SqlCommandType type;
  private long key;
  private long value;
  private long scanLowerInclusive;
  private long scanUpperExclusive;
  private long columnNotNullMask;
  private long columnDefaultMask;
  private long columnUniqueMask;
  private long columnReferenceMask;
  private long rowLimit = Long.MAX_VALUE;
  private long sequenceStart = 1;
  private long sequenceIncrement = 1;
  private boolean boundedScan;
  private boolean selectAll;
  private boolean readCommittedTransaction;
  private boolean serializableTransaction;
  private boolean descendingOrder;
  private boolean primaryKeyIdentity;
  private int insertRowCount;
  private int insertColumnCount;
  private int updateColumnCount;
  private int columnCount;
  private boolean available;
  private int textBytesUsed;

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
    tableName.reset();
    renamedTableName.reset();
    tableAlias.reset();
    indexName.reset();
    renamedIndexName.reset();
    sequenceName.reset();
    savepointName.reset();
    viewQuery.reset();
    scalarExpression.reset();
    projections.reset();
    mutationExpressions.reset();
    aggregates.reset();
    wherePredicates.reset();
    if (joinChain != null) joinChain.reset();
    booleanHavingPredicates.reset();
    for (SqlIdentifier columnName : columnNames) {
      columnName.reset();
    }
    for (int index = 0; index < nullProjections.length; index++) {
      nullProjections[index] = false;
      columnCheckValues[index] = 0;
      columnCheckTypeDescriptors[index] = 0;
      columnCheckComparisons[index] = null;
      columnTypeDescriptors[index] = 0;
      columnDefaultKinds[index] = 0;
      columnReferenceTableNames[index].reset();
      columnReferenceColumnNames[index].reset();
    }
    for (SqlIdentifier columnTableName : columnTableNames) {
      columnTableName.reset();
    }
    for (SqlIdentifier columnAlias : columnAliases) {
      columnAlias.reset();
    }
    orderColumnName.reset();
    type = null;
    key = 0;
    value = 0;
    scanLowerInclusive = 0;
    scanUpperExclusive = 0;
    columnNotNullMask = 0;
    columnDefaultMask = 0;
    columnUniqueMask = 0;
    columnReferenceMask = 0;
    rowLimit = Long.MAX_VALUE;
    sequenceStart = 1;
    sequenceIncrement = 1;
    boundedScan = false;
    selectAll = false;
    readCommittedTransaction = false;
    serializableTransaction = false;
    descendingOrder = false;
    primaryKeyIdentity = false;
    insertRowCount = 0;
    insertColumnCount = 0;
    updateColumnCount = 0;
    columnCount = 0;
    available = false;
    textBytesUsed = 0;
    for (int index = 0; index < insertNullMasks.length; index++) {
      insertNullMasks[index] = 0;
      insertDefaultMasks[index] = 0;
      int valueOffset = index * MAXIMUM_COLUMNS;
      for (int column = 0; column < MAXIMUM_COLUMNS; column++) {
        insertTypeDescriptors[valueOffset + column] = 0;
      }
    }
    for (int index = 0; index < nullUpdates.length; index++) {
      nullUpdates[index] = false;
      defaultUpdates[index] = false;
      updateTypeDescriptors[index] = 0;
      updateOperators[index] = UPDATE_LITERAL;
    }
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
      StatusCode status = setProjectionColumn(index, "", column);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  void setRowLimit(long maximumRows) {
    rowLimit = maximumRows;
  }

  void copyQueryFrom(SqlCommand source) {
    reset();
    scalarExpression.copyFrom(source.scalarExpression);
    projections.copyFrom(source.projections);
    aggregates.copyFrom(source.aggregates);
    wherePredicates.copyFrom(source.wherePredicates);
    boolean joined = source.joinChain != null && source.joinChain.stageCount() > 0;
    if (joined) {
      writableJoinChain().copyFrom(source.joinChain);
    } else {
      tableName.copyFrom(source.tableName);
      tableAlias.copyFrom(source.tableAlias);
    }
    booleanHavingPredicates.copyFrom(source.booleanHavingPredicates);
    System.arraycopy(source.textBytes, 0, textBytes, 0, source.textBytesUsed);
    textBytesUsed = source.textBytesUsed;
    for (int index = 0; index < source.columnCount; index++) {
      writableNextColumnName().copyFrom(source.columnNames[index]);
      writableColumnTableName(index).copyFrom(source.columnTableNames[index]);
      writableColumnAlias(index).copyFrom(source.columnAliases[index]);
      nullProjections[index] = source.nullProjections[index];
    }
    orderColumnName.copyFrom(source.orderColumnName);
    descendingOrder = source.descendingOrder;
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
  }

  void appendInsert(
      long[] values,
      long nullMask,
      long defaultMask,
      int[] typeDescriptors,
      int count) {
    int destination = insertRowCount * MAXIMUM_COLUMNS;
    for (int index = 0; index < count; index++) {
      insertValues[destination + index] = values[index];
      insertTypeDescriptors[destination + index] = typeDescriptors[index];
    }
    insertColumnCount = count;
    insertNullMasks[insertRowCount] = nullMask;
    insertDefaultMasks[insertRowCount] = defaultMask;
    insertRowCount++;
  }

  void setInsert() {
    type = SqlCommandType.INSERT;
    key = insertValues[0];
    value = insertValues[1];
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
    updateValues[updateColumnCount] = updateValue;
    nullUpdates[updateColumnCount] = isNull;
    defaultUpdates[updateColumnCount] = isDefault;
    updateTypeDescriptors[updateColumnCount] = typeDescriptor;
    updateOperators[updateColumnCount] = operator;
    updateColumnCount++;
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
    return index >= 0 && index < columnCount ? projections.expression(index) : null;
  }

  int appendMutationExpression(SqlScalarExpression expression) {
    return mutationExpressions.append(expression);
  }

  int registerProjectionSymbol(CharSequence table, CharSequence name) {
    return projections.registerSymbol(table, name);
  }

  SqlBooleanPredicateProgram writableWherePredicates() {
    return wherePredicates;
  }

  SqlBooleanPredicateProgram writableBooleanHavingPredicates() {
    return booleanHavingPredicates;
  }

  int registerPredicateSymbol(CharSequence table, CharSequence name) {
    return projections.registerSymbol(table, name);
  }

  void adoptDirectProjectionName(int index) {
    SqlScalarExpression expression = projectionExpression(index);
    int symbol = expression != null && expression.isDirectColumnReference()
        ? (int) expression.operand(0) : -1;
    SqlIdentifier name = projections.symbolName(symbol);
    SqlIdentifier table = projections.symbolTable(symbol);
    if (name != null && table != null && index >= 0 && index < columnCount) {
      columnNames[index].copyFrom(name);
      columnTableNames[index].copyFrom(table);
    }
  }

  StatusCode setProjectionColumn(
      int index, CharSequence table, CharSequence name) {
    SqlScalarExpression expression = writableProjectionExpression(index);
    int symbol = registerProjectionSymbol(table, name);
    if (expression == null || symbol < 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expression.reset();
    if (!expression.append(SqlScalarExpression.COLUMN, symbol, 0)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expression.finishUnresolved();
    adoptDirectProjectionName(index);
    return StatusCode.OK;
  }

  StatusCode setProjectionNull(int index) {
    SqlScalarExpression expression = writableProjectionExpression(index);
    if (expression == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expression.reset();
    if (!expression.append(SqlScalarExpression.NULL, 0, 0)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expression.finishUnresolved();
    markLastProjectionNull();
    return StatusCode.OK;
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
      columnDefaultKinds[column] = io.riverdb.base.type.SqlDefaultKind.LITERAL;
    }
  }

  void markLastColumnCurrentDefault(int kind) {
    if (columnCount > 1) {
      int column = columnCount - 1;
      columnDefaultMask |= 1L << column;
      columnDefaultValues[column] = 0;
      columnDefaultKinds[column] = (byte) kind;
    }
  }

  void markLastColumnVarchar(int maximumScalars) {
    markLastColumnType(SqlTypeDescriptor.varchar(maximumScalars));
  }

  void markLastColumnType(int descriptor) {
    if (columnCount > 0 && SqlTypeDescriptor.isValid(descriptor)) {
      columnTypeDescriptors[columnCount - 1] = descriptor;
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

  void markLastColumnCheck(SqlComparison comparison, long value, int descriptor) {
    if (columnCount > 0) {
      int column = columnCount - 1;
      columnCheckComparisons[column] = comparison;
      columnCheckValues[column] = value;
      columnCheckTypeDescriptors[column] = descriptor;
    }
  }

  long storeText(char[] source, int offset, int length) {
    int bytes = Utf8Text.encode(
        source,
        offset,
        length,
        Utf8Text.MAXIMUM_SCALARS,
        textBytes,
        textBytesUsed);
    if (bytes < 0) {
      return INVALID_TEXT_HANDLE;
    }
    long handle = (long) textBytesUsed << 32 | Integer.toUnsignedLong(bytes);
    textBytesUsed += bytes;
    return handle;
  }

  long copyTextFrom(SqlCommand source, long handle) {
    int length = source == null ? -1 : source.textByteLength(handle);
    if (length < 0 || length > textBytes.length - textBytesUsed) {
      return INVALID_TEXT_HANDLE;
    }
    int sourceOffset = (int) (handle >>> 32);
    int destinationOffset = textBytesUsed;
    System.arraycopy(
        source.textBytes, sourceOffset, textBytes, destinationOffset, length);
    textBytesUsed += length;
    return (long) destinationOffset << 32 | Integer.toUnsignedLong(length);
  }

  long textSuccessor(long handle) {
    int length = textByteLength(handle);
    if (length < 0 || length >= textBytes.length - textBytesUsed) {
      return INVALID_TEXT_HANDLE;
    }
    int sourceOffset = (int) (handle >>> 32);
    int destinationOffset = textBytesUsed;
    System.arraycopy(
        textBytes, sourceOffset, textBytes, destinationOffset, length);
    textBytes[destinationOffset + length] = 0;
    textBytesUsed += length + 1;
    return (long) destinationOffset << 32
        | Integer.toUnsignedLong(length + 1);
  }

  int compareText(long left, long right) {
    int leftLength = textByteLength(left);
    int rightLength = textByteLength(right);
    if (leftLength < 0 || rightLength < 0) return Integer.MIN_VALUE;
    int leftOffset = (int) (left >>> 32);
    int rightOffset = (int) (right >>> 32);
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Byte.toUnsignedInt(textBytes[leftOffset + index])
          - Byte.toUnsignedInt(textBytes[rightOffset + index]);
      if (compared != 0) return compared;
    }
    return leftLength - rightLength;
  }

  public int textByteLength(long handle) {
    int textOffset = (int) (handle >>> 32);
    int length = (int) handle;
    return textOffset >= 0
            && length >= 0
            && textOffset <= textBytesUsed - length
        ? length : -1;
  }

  public int copyText(long handle, ByteBuffer target) {
    int textOffset = (int) (handle >>> 32);
    int length = textByteLength(handle);
    if (length < 0 || target == null || target.remaining() < length) {
      return -1;
    }
    target.put(textBytes, textOffset, length);
    return length;
  }

  public byte textByteAt(long handle, int index) {
    int textOffset = (int) (handle >>> 32);
    int length = textByteLength(handle);
    return index >= 0 && index < length ? textBytes[textOffset + index] : 0;
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
    return index >= 0 && index < columnCount ? projections.expression(index) : null;
  }

  /** Returns a physical aggregate operand lane, including hidden HAVING operands. */
  public SqlScalarExpression aggregateOperandExpression(int index) {
    return index >= 0 && index < MAXIMUM_COLUMNS
        ? projections.expression(index) : null;
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
    return projections.symbolTable(index);
  }

  public SqlIdentifier predicateSymbolName(int index) {
    return projections.symbolName(index);
  }

  public int projectionSymbolCount() {
    return projections.symbolCount();
  }

  public SqlIdentifier projectionSymbolTable(int index) {
    return projections.symbolTable(index);
  }

  public SqlIdentifier projectionSymbolName(int index) {
    return projections.symbolName(index);
  }

  public int directProjectionSymbol(int index) {
    SqlScalarExpression expression = projectionExpression(index);
    return expression != null && expression.isDirectColumnReference()
        ? (int) expression.operand(0) : -1;
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
    return index >= 0 && index < columnCount && nullProjections[index];
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

  public boolean updateHasExpression(int index) {
    return updateOperator(index) == UPDATE_EXPRESSION;
  }

  public int updateExpression(int index) {
    return updateHasExpression(index) ? (int) updateValue(index) : -1;
  }

  public int mutationExpressionCount() {
    return mutationExpressions.programCount();
  }

  public int mutationExpressionNodeCount(int expression) {
    return mutationExpressions.nodeCount(expression);
  }

  public int mutationExpressionOperator(int expression, int node) {
    return mutationExpressions.operator(expression, node);
  }

  public long mutationExpressionOperand(int expression, int node) {
    return mutationExpressions.operand(expression, node);
  }

  public int mutationExpressionTypeDescriptor(int expression, int node) {
    return mutationExpressions.descriptor(expression, node);
  }

  public int updateOperator(int index) {
    return index >= 0 && index < updateColumnCount
        ? updateOperators[index] : UPDATE_LITERAL;
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

  public int insertTypeDescriptor(int rowIndex, int columnIndex) {
    return rowIndex >= 0
            && rowIndex < insertRowCount
            && columnIndex >= 0
            && columnIndex < insertColumnCount
        ? insertTypeDescriptors[rowIndex * MAXIMUM_COLUMNS + columnIndex] : 0;
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
    return index >= 0 && index < updateColumnCount && nullUpdates[index];
  }

  public boolean updateIsDefault(int index) {
    return index >= 0 && index < updateColumnCount && defaultUpdates[index];
  }

  public int updateTypeDescriptor(int index) {
    return index >= 0 && index < updateColumnCount ? updateTypeDescriptors[index] : 0;
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
    return aggregates.invocationCount();
  }

  public int aggregateOutputCount() {
    return aggregates.outputCount();
  }

  public int aggregateKind(int invocation) {
    return invocation >= 0 && invocation < aggregates.invocationCount()
        ? aggregates.kind(invocation) : 0;
  }

  public int aggregateOperandProjection(int invocation) {
    return invocation >= 0 && invocation < aggregates.invocationCount()
        ? aggregates.operandProjection(invocation) : -1;
  }

  public int aggregateOutputInvocation(int output) {
    return output >= 0 && output < aggregates.outputCount()
        ? aggregates.outputInvocation(output) : -1;
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
        if (Character.isHighSurrogate(character)) {
          if (++index >= end || !Character.isLowSurrogate(source.charAt(index))) {
            return StatusCode.INVALID_EXTERNAL_INPUT;
          }
          characters[index - start - 1] = character;
          characters[index - start] = source.charAt(index);
        } else if (Character.isLowSurrogate(character)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        } else {
          characters[index - start] = character;
        }
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
