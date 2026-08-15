package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.nio.ByteBuffer;

/** Caller-owned bounded schema carrying one canonical descriptor per column. */
public final class TableSchema {
  public static final int MAXIMUM_COLUMNS = 8;
  public static final int MAXIMUM_ROW_BYTES = 4096;
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
  public static final int MAXIMUM_CHECK_NODES = 32;
  static final int MAXIMUM_NAME_LENGTH = 64;

  private final ColumnName[] columns = new ColumnName[MAXIMUM_COLUMNS];
  private final long[] defaultValues = new long[MAXIMUM_COLUMNS];
  private final byte[] defaultKinds = new byte[MAXIMUM_COLUMNS];
  private final int[] typeDescriptors = new int[MAXIMUM_COLUMNS];
  private final long[] checkValues = new long[MAXIMUM_COLUMNS];
  private final int[] checkComparisons = new int[MAXIMUM_COLUMNS];
  private final int[] checkTypeDescriptors = new int[MAXIMUM_COLUMNS];
  private final byte[] checkNodeCounts = new byte[MAXIMUM_COLUMNS];
  private final byte[] checkOperators = new byte[MAXIMUM_CHECK_NODES];
  private final long[] checkOperands = new long[MAXIMUM_CHECK_NODES];
  private final int[] checkNodeDescriptors = new int[MAXIMUM_CHECK_NODES];
  private final int[] checkValidationStack = new int[MAXIMUM_CHECK_NODES];
  private final int[] referenceTableIds = new int[MAXIMUM_COLUMNS];
  private final byte[] defaultTextBytes = new byte[MAXIMUM_ROW_BYTES];
  private int columnCount;
  private long notNullMask;
  private long defaultMask;
  private long checkMask;
  private long referenceMask;
  private boolean identity;
  private int defaultTextBytesUsed;
  private int checkNodeCount;
  private int lastCheckColumn = -1;

  public TableSchema() {
    for (int index = 0; index < columns.length; index++) {
      columns[index] = new ColumnName();
    }
  }

  public void reset() {
    for (int index = 0; index < columnCount; index++) {
      columns[index].reset();
      typeDescriptors[index] = 0;
      defaultKinds[index] = SqlDefaultKind.NONE;
      checkNodeCounts[index] = 0;
      checkTypeDescriptors[index] = 0;
    }
    columnCount = 0;
    notNullMask = 0;
    defaultMask = 0;
    checkMask = 0;
    checkNodeCount = 0;
    lastCheckColumn = -1;
    referenceMask = 0;
    identity = false;
    defaultTextBytesUsed = 0;
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

  public StatusCode addVarchar(
      CharSequence name,
      int maximumScalars,
      boolean nullable) {
    int descriptor = SqlTypeDescriptor.varchar(maximumScalars);
    return descriptor == 0
        ? StatusCode.INVALID_EXTERNAL_INPUT : addColumn(name, nullable, descriptor);
  }

  public StatusCode addColumn(
      CharSequence name,
      int descriptor,
      boolean nullable) {
    int typeId = SqlTypeDescriptor.typeId(descriptor);
    if (!SqlTypeDescriptor.isValid(descriptor)
        || typeId != SqlTypeDescriptor.TYPE_ID_BIGINT
            && typeId != SqlTypeDescriptor.TYPE_ID_VARCHAR
            && typeId != SqlTypeDescriptor.TYPE_ID_BOOLEAN
            && typeId != SqlTypeDescriptor.TYPE_ID_DECIMAL
            && typeId != SqlTypeDescriptor.TYPE_ID_DATE
            && typeId != SqlTypeDescriptor.TYPE_ID_TIME
            && typeId != SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            && typeId != SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return addColumn(name, nullable, descriptor);
  }

  private StatusCode addColumn(
      CharSequence name,
      boolean nullable,
      int descriptor) {
    if (!RelationalKey.validName(name)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (columnCount >= columns.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int addedBytes = Long.BYTES;
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      addedBytes += SqlTypeDescriptor.parameterOne(descriptor) * 4;
    }
    if (maximumRowBytes() > MAXIMUM_ROW_BYTES - addedBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (find(name) >= 0) {
      return StatusCode.CONFLICT;
    }
    if (columnCount == 0 && nullable) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    columns[columnCount].set(name);
    if (!nullable) {
      notNullMask |= 1L << columnCount;
    }
    typeDescriptors[columnCount] = descriptor;
    columnCount++;
    return StatusCode.OK;
  }

  public StatusCode setLastDefault(long value) {
    if (columnCount <= 1) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int column = columnCount - 1;
    if (!validFixedValue(typeDescriptors[column], value)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    defaultMask |= 1L << column;
    defaultValues[column] = value;
    defaultKinds[column] = SqlDefaultKind.LITERAL;
    return StatusCode.OK;
  }

  public StatusCode setLastCurrentDefault(int kind) {
    if (columnCount <= 1) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int column = columnCount - 1;
    if (!SqlDefaultKind.compatible(kind, typeDescriptors[column])) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    defaultMask |= 1L << column;
    defaultValues[column] = 0;
    defaultKinds[column] = (byte) kind;
    return StatusCode.OK;
  }

  public StatusCode setLastTextDefault(ByteBuffer source) {
    if (columnCount <= 1 || source == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int column = columnCount - 1;
    int maximumScalars = SqlTypeDescriptor.parameterOne(typeDescriptors[column]);
    int length = source.remaining();
    if (SqlTypeDescriptor.typeId(typeDescriptors[column])
            != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || defaultTextBytesUsed > defaultTextBytes.length - length
        || Utf8Text.validate(
            source, source.position(), length, maximumScalars) < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = defaultTextBytesUsed;
    for (int index = 0; index < length; index++) {
      defaultTextBytes[defaultTextBytesUsed++] = source.get(source.position() + index);
    }
    defaultMask |= 1L << column;
    defaultValues[column] = (long) start << 32 | Integer.toUnsignedLong(length);
    defaultKinds[column] = SqlDefaultKind.LITERAL;
    return StatusCode.OK;
  }

  public StatusCode setPrimaryKeyIdentity() {
    if (columnCount < 1
        || (defaultMask & 1L) != 0
        || typeDescriptors[0] != SqlTypeDescriptor.BIGINT) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    identity = true;
    return StatusCode.OK;
  }

  public StatusCode setLastCheck(int comparison, long value) {
    int column = columnCount - 1;
    if (column < 0
        || SqlTypeDescriptor.typeId(typeDescriptors[column])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR
        || !validFixedValue(typeDescriptors[column], value)
        || (checkMask & 1L << column) != 0
        || column <= lastCheckColumn
        || !validCheckComparison(comparison)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    byte[] operators = {(byte) CHECK_COLUMN};
    long[] operands = {column};
    int[] descriptors = {typeDescriptors[column]};
    return setCheck(
        column,
        comparison,
        typeDescriptors[column],
        value,
        1,
        operators,
        operands,
        descriptors);
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
        || (checkMask & 1L << column) != 0
        || column <= lastCheckColumn
        || !validCheckComparison(comparison)
        || SqlTypeDescriptor.typeId(valueDescriptor)
                == SqlTypeDescriptor.TYPE_ID_BOOLEAN
            && comparison != CHECK_EQUAL
            && comparison != CHECK_NOT_EQUAL
        || valueDescriptor == 0
        || !validFixedValue(valueDescriptor, value)
        || nodes <= 0
        || nodes > MAXIMUM_CHECK_NODES - checkNodeCount
        || !TableCheckProgram.valid(
            column,
            typeDescriptors[column],
            valueDescriptor,
            nodes,
            operators,
            operands,
            descriptors,
            checkValidationStack)) {
      return nodes > MAXIMUM_CHECK_NODES - checkNodeCount
          ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int offset = checkNodeCount;
    System.arraycopy(operators, 0, checkOperators, offset, nodes);
    System.arraycopy(operands, 0, checkOperands, offset, nodes);
    System.arraycopy(descriptors, 0, checkNodeDescriptors, offset, nodes);
    checkNodeCount += nodes;
    lastCheckColumn = column;
    checkMask |= 1L << column;
    checkComparisons[column] = comparison;
    checkValues[column] = value;
    checkTypeDescriptors[column] = valueDescriptor;
    checkNodeCounts[column] = (byte) nodes;
    return StatusCode.OK;
  }

  public StatusCode setLastReference(int tableId) {
    return setReference(columnCount - 1, tableId);
  }

  public StatusCode setReference(int column, int tableId) {
    if (column <= 0
        || column >= columnCount
        || tableId <= 0
        || typeDescriptors[column] != SqlTypeDescriptor.BIGINT
        || (referenceMask & 1L << column) != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    referenceMask |= 1L << column;
    referenceTableIds[column] = tableId;
    return StatusCode.OK;
  }

  public int columnCount() {
    return columnCount;
  }

  public CharSequence columnName(int index) {
    return index >= 0 && index < columnCount ? columns[index] : null;
  }

  public int find(CharSequence name) {
    for (int index = 0; index < columnCount; index++) {
      if (columns[index].matches(name)) {
        return index;
      }
    }
    return -1;
  }

  boolean isValid() {
    return columnCount >= 2 && (notNullMask & 1L) != 0;
  }

  long notNullMask() {
    return notNullMask;
  }

  long defaultMask() {
    return defaultMask;
  }

  long defaultValue(int column) {
    return column >= 0
            && column < columnCount
            && (defaultMask & 1L << column) != 0
        ? defaultValues[column] : 0;
  }

  int defaultKind(int column) {
    return column >= 0 && column < columnCount ? defaultKinds[column] : 0;
  }

  int defaultTextBytes() {
    return defaultTextBytesUsed;
  }

  byte defaultTextByte(int index) {
    return index >= 0 && index < defaultTextBytesUsed ? defaultTextBytes[index] : 0;
  }

  boolean isVarchar(int column) {
    return column > 0
        && column < columnCount
        && SqlTypeDescriptor.typeId(typeDescriptors[column])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  public boolean hasIdentity() {
    return identity;
  }

  public int typeDescriptor(int column) {
    return column >= 0 && column < columnCount ? typeDescriptors[column] : 0;
  }

  public int maximumRowBytes() {
    int bytes = columnCount * Long.BYTES;
    for (int column = 1; column < columnCount; column++) {
      if (SqlTypeDescriptor.typeId(typeDescriptors[column])
          == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        bytes += SqlTypeDescriptor.parameterOne(typeDescriptors[column]) * 4;
      }
    }
    return bytes;
  }

  long checkMask() {
    return checkMask;
  }

  int checkComparison(int column) {
    return column >= 0 && column < columnCount ? checkComparisons[column] : 0;
  }

  long checkValue(int column) {
    return column >= 0 && column < columnCount ? checkValues[column] : 0;
  }

  int checkTypeDescriptor(int column) {
    return column >= 0 && column < columnCount ? checkTypeDescriptors[column] : 0;
  }

  int checkNodeCount(int column) {
    return column >= 0 && column < columnCount
        ? Byte.toUnsignedInt(checkNodeCounts[column]) : 0;
  }

  int checkNodeCount() {
    return checkNodeCount;
  }

  int checkOperator(int node) {
    return node >= 0 && node < checkNodeCount
        ? Byte.toUnsignedInt(checkOperators[node]) : 0;
  }

  long checkOperand(int node) {
    return node >= 0 && node < checkNodeCount ? checkOperands[node] : 0;
  }

  int checkNodeDescriptor(int node) {
    return node >= 0 && node < checkNodeCount ? checkNodeDescriptors[node] : 0;
  }

  long referenceMask() {
    return referenceMask;
  }

  int referenceTableId(int column) {
    return column >= 0 && column < columnCount ? referenceTableIds[column] : 0;
  }

  static boolean validCheckComparison(int comparison) {
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
      for (int index = 0; index < length; index++) {
        characters[index] = name.charAt(index);
      }
    }

    void set(java.nio.ByteBuffer source, int offset, int bytes) {
      length = bytes;
      for (int index = 0; index < length; index++) {
        characters[index] = (char) Byte.toUnsignedInt(source.get(offset + index));
      }
    }

    boolean matches(CharSequence name) {
      if (name == null || name.length() != length) {
        return false;
      }
      for (int index = 0; index < length; index++) {
        if (name.charAt(index) != characters[index]) {
          return false;
        }
      }
      return true;
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
      if (start < 0 || end < start || end > length) {
        throw new IndexOutOfBoundsException(start);
      }
      return new String(characters, start, end - start);
    }
  }
}
