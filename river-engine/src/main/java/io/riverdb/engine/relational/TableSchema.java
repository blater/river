package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.nio.ByteBuffer;

/** Caller-owned reusable schema carrying one canonical descriptor per admitted column. */
public final class TableSchema {
  public static final int MAXIMUM_ROW_BYTES = SqlShapeLimits.MAX_STORED_ROW_BYTES;
  public static final int CHECK_EQUAL = 1;
  public static final int CHECK_NOT_EQUAL = 2;
  public static final int CHECK_LESS_THAN = 3;
  public static final int CHECK_LESS_OR_EQUAL = 4;
  public static final int CHECK_GREATER_THAN = 5;
  public static final int CHECK_GREATER_OR_EQUAL = 6;
  public static final int CHECK_LITERAL = 1;
  public static final int CHECK_COLUMN = 2;
  public static final int CHECK_ADD = 3;
  public static final int CHECK_SUBTRACT = 4;
  public static final int CHECK_CAST = 5;
  public static final int CHECK_EXTRACT = 6;
  public static final int MAXIMUM_CHECK_PROGRAM_NODES = SqlShapeLimits.MAX_EXPRESSION_NODES;
  static final int MAXIMUM_NAME_LENGTH = 64;

  private final TableSchemaStorage storage = new TableSchemaStorage();
  private int columnCount;
  private boolean identity;
  private int defaultTextBytesUsed;
  private int checkNodeCount;
  private int lastCheckColumn = -1;

  public void reset() {
    for (int index = 0; index < columnCount; index++) {
      storage.columns[index].reset();
      storage.typeDescriptors[index] = 0;
      storage.defaultKinds[index] = SqlDefaultKind.NONE;
      storage.checkNodeCounts[index] = 0;
      storage.checkTypeDescriptors[index] = 0;
      storage.referenceTableIds[index] = 0;
    }
    storage.clearBits();
    columnCount = 0;
    identity = false;
    defaultTextBytesUsed = 0;
    checkNodeCount = 0;
    lastCheckColumn = -1;
  }

  public StatusCode addBigint(CharSequence name) {
    return addBigint(name, columnCount > 0);
  }

  public StatusCode addBigint(CharSequence name, boolean nullable) {
    return addColumn(name, nullable, SqlTypeDescriptor.BIGINT);
  }

  public StatusCode addVarchar7(CharSequence name, boolean nullable) {
    return addVarchar(name, 7, nullable);
  }

  public StatusCode addVarchar(CharSequence name, int maximumScalars, boolean nullable) {
    int descriptor = SqlTypeDescriptor.varchar(maximumScalars);
    return descriptor == 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : addColumn(name, nullable, descriptor);
  }

  public StatusCode addColumn(CharSequence name, int descriptor, boolean nullable) {
    if (!SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return addColumn(name, nullable, descriptor);
  }

  private StatusCode addColumn(CharSequence name, boolean nullable, int descriptor) {
    if (!RelationalKey.validName(name)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (columnCount >= SqlShapeLimits.MAX_TABLE_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int addedBytes = storage.addedRowBytes(descriptor, columnCount);
    if (maximumRowBytes() > MAXIMUM_ROW_BYTES - addedBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (find(name) >= 0) return StatusCode.CONFLICT;
    if (columnCount == 0 && nullable) return StatusCode.INVALID_EXTERNAL_INPUT;

    StatusCode capacity = storage.reserveColumns(columnCount + 1, columnCount);
    if (!capacity.isOk()) return capacity;
    capacity = storage.reserveColumnName(columnCount);
    if (!capacity.isOk()) return capacity;
    storage.publishColumn(columnCount, name, nullable, descriptor);
    columnCount++;
    return StatusCode.OK;
  }

  public StatusCode setLastDefault(long value) {
    if (columnCount <= 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int column = columnCount - 1;
    if (!io.riverdb.base.type.SqlValueDomain.validFixed(
        storage.typeDescriptors[column], value)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    storage.defaultColumns.set(column);
    storage.defaultValues[column] = value;
    storage.defaultKinds[column] = SqlDefaultKind.LITERAL;
    return StatusCode.OK;
  }

  public StatusCode setLastCurrentDefault(int kind) {
    if (columnCount <= 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int column = columnCount - 1;
    if (!SqlDefaultKind.compatible(kind, storage.typeDescriptors[column])) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    storage.defaultColumns.set(column);
    storage.defaultValues[column] = 0;
    storage.defaultKinds[column] = (byte) kind;
    return StatusCode.OK;
  }

  public StatusCode setLastTextDefault(ByteBuffer source) {
    if (columnCount <= 1 || source == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    int column = columnCount - 1;
    int maximumScalars = SqlTypeDescriptor.parameterOne(storage.typeDescriptors[column]);
    int length = source.remaining();
    if (SqlTypeDescriptor.typeId(storage.typeDescriptors[column])
            != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || Utf8Text.validate(source, source.position(), length, maximumScalars) < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode capacity = storage.reserveText(defaultTextBytesUsed + length, defaultTextBytesUsed);
    if (!capacity.isOk()) return capacity;
    int start = defaultTextBytesUsed;
    for (int index = 0; index < length; index++) {
      storage.defaultTextBytes[start + index] = source.get(source.position() + index);
    }
    defaultTextBytesUsed += length;
    storage.defaultColumns.set(column);
    storage.defaultValues[column] = (long) start << 32 | Integer.toUnsignedLong(length);
    storage.defaultKinds[column] = SqlDefaultKind.LITERAL;
    return StatusCode.OK;
  }

  public StatusCode setPrimaryKeyIdentity() {
    if (columnCount < 1
        || storage.defaultColumns.get(0)
        || storage.typeDescriptors[0] != SqlTypeDescriptor.BIGINT) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    identity = true;
    return StatusCode.OK;
  }

  public StatusCode setLastCheck(int comparison, long value) {
    int column = columnCount - 1;
    if (column < 0
        || SqlTypeDescriptor.typeId(storage.typeDescriptors[column])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR
        || !io.riverdb.base.type.SqlValueDomain.validFixed(
            storage.typeDescriptors[column], value)
        || storage.checkColumns.get(column)
        || column <= lastCheckColumn
        || !validCheckComparison(comparison)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      byte[] operators = {(byte) CHECK_COLUMN};
      long[] operands = {column};
      int[] descriptors = {storage.typeDescriptors[column]};
      return setCheck(
          column, comparison, storage.typeDescriptors[column], value,
          1, operators, operands, descriptors);
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  public StatusCode setCheck(
      int column,
      int comparison,
      int valueDescriptor,
      long value,
      int nodes,
      byte[] operators,
      long[] operands,
      int[] descriptors) {
    if (column < 0 || column >= columnCount
        || storage.checkColumns.get(column)
        || column <= lastCheckColumn
        || !validCheckComparison(comparison)
        || valueDescriptor == 0
        || !io.riverdb.base.type.SqlValueDomain.validFixed(valueDescriptor, value)
        || nodes <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (nodes > MAXIMUM_CHECK_PROGRAM_NODES - checkNodeCount) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode capacity = storage.reserveChecks(checkNodeCount + nodes, checkNodeCount);
    if (!capacity.isOk()) return capacity;
    if (!TableCheckProgram.valid(
        column,
        storage.typeDescriptors[column],
        valueDescriptor,
        nodes,
        operators,
        operands,
        descriptors,
        storage.checkValidationStack)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int offset = checkNodeCount;
    System.arraycopy(operators, 0, storage.checkOperators, offset, nodes);
    System.arraycopy(operands, 0, storage.checkOperands, offset, nodes);
    System.arraycopy(descriptors, 0, storage.checkNodeDescriptors, offset, nodes);
    checkNodeCount += nodes;
    lastCheckColumn = column;
    storage.checkColumns.set(column);
    storage.checkComparisons[column] = comparison;
    storage.checkValues[column] = value;
    storage.checkTypeDescriptors[column] = valueDescriptor;
    storage.checkNodeCounts[column] = nodes;
    return StatusCode.OK;
  }

  public StatusCode setLastReference(int tableId) {
    return setReference(columnCount - 1, tableId);
  }

  public StatusCode setReference(int column, int tableId) {
    if (column <= 0
        || column >= columnCount
        || tableId <= 0
        || storage.typeDescriptors[column] != SqlTypeDescriptor.BIGINT
        || storage.referenceColumns.get(column)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    storage.referenceColumns.set(column);
    storage.referenceTableIds[column] = tableId;
    return StatusCode.OK;
  }

  public int columnCount() {
    return columnCount;
  }

  public CharSequence columnName(int index) {
    return index >= 0 && index < columnCount ? storage.columns[index] : null;
  }

  public int find(CharSequence name) {
    return storage.find(name, columnCount);
  }

  boolean isValid() {
    return columnCount >= 2 && storage.notNullColumns.get(0);
  }

  long notNullWord(int word) {
    return storage.notNullColumns.word(word);
  }

  long defaultWord(int word) {
    return storage.defaultColumns.word(word);
  }

  long defaultValue(int column) {
    return column >= 0 && column < columnCount && storage.defaultColumns.get(column)
        ? storage.defaultValues[column] : 0;
  }

  int defaultKind(int column) {
    return column >= 0 && column < columnCount ? storage.defaultKinds[column] : 0;
  }

  int defaultTextBytes() {
    return defaultTextBytesUsed;
  }

  byte defaultTextByte(int index) {
    return index >= 0 && index < defaultTextBytesUsed
        ? storage.defaultTextBytes[index] : 0;
  }

  boolean isVarchar(int column) {
    return storage.isVarchar(column, columnCount);
  }

  public boolean hasIdentity() {
    return identity;
  }

  public int typeDescriptor(int column) {
    return column >= 0 && column < columnCount ? storage.typeDescriptors[column] : 0;
  }

  public int maximumRowBytes() {
    return storage.maximumRowBytes(columnCount);
  }

  long checkWord(int word) {
    return storage.checkColumns.word(word);
  }

  int checkComparison(int column) {
    return column >= 0 && column < columnCount ? storage.checkComparisons[column] : 0;
  }

  long checkValue(int column) {
    return column >= 0 && column < columnCount ? storage.checkValues[column] : 0;
  }

  int checkTypeDescriptor(int column) {
    return column >= 0 && column < columnCount
        ? storage.checkTypeDescriptors[column] : 0;
  }

  int checkNodeCount(int column) {
    return column >= 0 && column < columnCount
        ? storage.checkNodeCounts[column] : 0;
  }

  int checkNodeCount() {
    return checkNodeCount;
  }

  int checkOperator(int node) {
    return node >= 0 && node < checkNodeCount
        ? Byte.toUnsignedInt(storage.checkOperators[node])
        : 0;
  }

  long checkOperand(int node) {
    return node >= 0 && node < checkNodeCount ? storage.checkOperands[node] : 0;
  }

  int checkNodeDescriptor(int node) {
    return node >= 0 && node < checkNodeCount ? storage.checkNodeDescriptors[node] : 0;
  }

  long referenceWord(int word) {
    return storage.referenceColumns.word(word);
  }

  int referenceTableId(int column) {
    return column >= 0 && column < columnCount && storage.referenceColumns.get(column)
        ? storage.referenceTableIds[column] : 0;
  }

  private static boolean validCheckComparison(int comparison) {
    return comparison >= CHECK_EQUAL && comparison <= CHECK_GREATER_OR_EQUAL;
  }

  public static boolean validFixedValue(int descriptor, long value) {
    return SqlValueDomain.validFixed(descriptor, value);
  }

  static final class ColumnName implements CharSequence {
    private final char[] characters = new char[MAXIMUM_NAME_LENGTH];
    private int length;

    void reset() {
      length = 0;
    }

    void set(CharSequence name) {
      length = name.length();
      for (int index = 0; index < length; index++) characters[index] = name.charAt(index);
    }

    void set(ByteBuffer source, int offset, int bytes) {
      length = bytes;
      for (int index = 0; index < length; index++) {
        characters[index] = (char) Byte.toUnsignedInt(source.get(offset + index));
      }
    }

    boolean matches(CharSequence name) {
      if (name == null || name.length() != length) return false;
      for (int index = 0; index < length; index++) {
        if (name.charAt(index) != characters[index]) return false;
      }
      return true;
    }

    @Override
    public int length() {
      return length;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
      return characters[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      if (start < 0 || end < start || end > length) {
        throw new IndexOutOfBoundsException(start);
      }
      return new String(characters, start, end - start);
    }
  }
}
