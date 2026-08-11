package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Caller-owned bounded schema for the current BIGINT and VARCHAR(7) relational slice. */
public final class TableSchema {
  public static final int MAXIMUM_COLUMNS = 8;
  static final int MAXIMUM_NAME_LENGTH = 64;

  private final ColumnName[] columns = new ColumnName[MAXIMUM_COLUMNS];
  private final long[] defaultValues = new long[MAXIMUM_COLUMNS];
  private int columnCount;
  private long notNullMask;
  private long defaultMask;
  private long varcharMask;
  private boolean identity;

  public TableSchema() {
    for (int index = 0; index < columns.length; index++) {
      columns[index] = new ColumnName();
    }
  }

  public void reset() {
    for (int index = 0; index < columnCount; index++) {
      columns[index].reset();
    }
    columnCount = 0;
    notNullMask = 0;
    defaultMask = 0;
    varcharMask = 0;
    identity = false;
  }

  public StatusCode addBigint(CharSequence name) {
    return addBigint(name, columnCount > 0);
  }

  public StatusCode addBigint(CharSequence name, boolean nullable) {
    return addColumn(name, nullable, false);
  }

  public StatusCode addVarchar7(CharSequence name, boolean nullable) {
    return addColumn(name, nullable, true);
  }

  private StatusCode addColumn(
      CharSequence name,
      boolean nullable,
      boolean varchar) {
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
    if (varchar) {
      varcharMask |= 1L << columnCount;
    }
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
    if (columnCount < 1 || (defaultMask & 1L) != 0 || (varcharMask & 1L) != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    identity = true;
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
        && (varcharMask & 1L << column) != 0;
  }

  public boolean hasIdentity() {
    return identity;
  }

  long varcharMask() {
    return varcharMask;
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
