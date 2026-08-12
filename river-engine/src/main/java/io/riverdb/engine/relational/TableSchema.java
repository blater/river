package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Caller-owned bounded schema carrying one canonical descriptor per column. */
public final class TableSchema {
  public static final int MAXIMUM_COLUMNS = 8;
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
  private int columnCount;
  private long notNullMask;
  private long defaultMask;
  private long checkMask;
  private long referenceMask;
  private boolean identity;

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
  }

  public StatusCode addBigint(CharSequence name) {
    return addBigint(name, columnCount > 0);
  }

  public StatusCode addBigint(CharSequence name, boolean nullable) {
    return addColumn(name, nullable, SqlTypeDescriptor.BIGINT);
  }

  public StatusCode addVarchar7(CharSequence name, boolean nullable) {
    return addColumn(name, nullable, SqlTypeDescriptor.varchar(7));
  }

  public StatusCode addColumn(
      CharSequence name,
      int descriptor,
      boolean nullable) {
    if (descriptor != SqlTypeDescriptor.BIGINT
        && descriptor != SqlTypeDescriptor.varchar(7)) {
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
    defaultMask |= 1L << column;
    defaultValues[column] = value;
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
        || typeDescriptors[column] != SqlTypeDescriptor.BIGINT
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
