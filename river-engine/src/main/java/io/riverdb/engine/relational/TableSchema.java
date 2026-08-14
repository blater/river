package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.ExactDecimal;
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
  static final int MAXIMUM_NAME_LENGTH = 64;

  private final ColumnName[] columns = new ColumnName[MAXIMUM_COLUMNS];
  private final long[] defaultValues = new long[MAXIMUM_COLUMNS];
  private final int[] typeDescriptors = new int[MAXIMUM_COLUMNS];
  private final long[] checkValues = new long[MAXIMUM_COLUMNS];
  private final int[] checkComparisons = new int[MAXIMUM_COLUMNS];
  private final int[] referenceTableIds = new int[MAXIMUM_COLUMNS];
  private final byte[] defaultTextBytes = new byte[MAXIMUM_ROW_BYTES];
  private int columnCount;
  private long notNullMask;
  private long defaultMask;
  private long checkMask;
  private long referenceMask;
  private boolean identity;
  private int defaultTextBytesUsed;

  public TableSchema() {
    for (int index = 0; index < columns.length; index++) {
      columns[index] = new ColumnName();
    }
  }

  public void reset() {
    for (int index = 0; index < columnCount; index++) {
      columns[index].reset();
      typeDescriptors[index] = 0;
    }
    columnCount = 0;
    notNullMask = 0;
    defaultMask = 0;
    checkMask = 0;
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
            && typeId != SqlTypeDescriptor.TYPE_ID_DECIMAL) {
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
        || !validCheckComparison(comparison)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    checkMask |= 1L << column;
    checkComparisons[column] = comparison;
    checkValues[column] = value;
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

  long referenceMask() {
    return referenceMask;
  }

  int referenceTableId(int column) {
    return column >= 0 && column < columnCount ? referenceTableIds[column] : 0;
  }

  static boolean validCheckComparison(int comparison) {
    return comparison >= CHECK_EQUAL && comparison <= CHECK_GREATER_OR_EQUAL;
  }

  static boolean validFixedValue(int descriptor, long value) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> true;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> value == 0 || value == 1;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          ExactDecimal.fits(value, SqlTypeDescriptor.parameterOne(descriptor));
      default -> false;
    };
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
