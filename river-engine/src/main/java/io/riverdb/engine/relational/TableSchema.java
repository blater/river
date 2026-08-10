package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Caller-owned bounded schema for the current all-BIGINT relational slice. */
public final class TableSchema {
  public static final int MAXIMUM_COLUMNS = 8;
  static final int MAXIMUM_NAME_LENGTH = 64;

  private final ColumnName[] columns = new ColumnName[MAXIMUM_COLUMNS];
  private int columnCount;

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
  }

  public StatusCode addBigint(CharSequence name) {
    if (!RelationalKey.validName(name)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (columnCount >= columns.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (find(name) >= 0) {
      return StatusCode.CONFLICT;
    }
    columns[columnCount++].set(name);
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
    return columnCount >= 2;
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
