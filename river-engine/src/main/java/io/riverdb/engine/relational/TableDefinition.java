package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Caller-owned resolved logical table identity. */
public final class TableDefinition {
  static final int INDEX_NONE = 0;
  static final int INDEX_BUILDING = 1;
  static final int INDEX_READY = 2;

  private RelationalDatabase owner;
  private int tableId;
  private int uniqueValueIndexTableId;
  private int uniqueValueIndexState;
  private final ColumnName keyColumnName = new ColumnName();
  private final ColumnName valueColumnName = new ColumnName();
  private final ColumnName[] additionalColumns =
      new ColumnName[TableSchema.MAXIMUM_COLUMNS - 2];
  private int uniqueValueIndexColumn = -1;
  private int columnCount;
  private long schemaVersion;
  private boolean available;

  public TableDefinition() {
    for (int index = 0; index < additionalColumns.length; index++) {
      additionalColumns[index] = new ColumnName();
    }
  }

  public void reset() {
    owner = null;
    tableId = 0;
    uniqueValueIndexTableId = 0;
    uniqueValueIndexState = INDEX_NONE;
    uniqueValueIndexColumn = -1;
    keyColumnName.reset();
    valueColumnName.reset();
    for (int index = 0; index < columnCount - 2; index++) {
      additionalColumns[index].reset();
    }
    columnCount = 0;
    schemaVersion = 0;
    available = false;
  }

  void set(
      RelationalDatabase database,
      int id,
      int valueIndexTableId,
      int valueIndexState) {
    set(database, id, valueIndexTableId, valueIndexState, "key", "value");
  }

  void set(
      RelationalDatabase database,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      CharSequence keyName,
      CharSequence valueName) {
    owner = database;
    tableId = id;
    uniqueValueIndexTableId = valueIndexTableId;
    uniqueValueIndexState = valueIndexState;
    uniqueValueIndexColumn = valueIndexTableId == 0 ? -1 : 1;
    keyColumnName.set(keyName);
    valueColumnName.set(valueName);
    columnCount = 2;
    schemaVersion = database.schemaVersion();
    available = true;
  }

  void set(
      RelationalDatabase database,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableDefinition schema) {
    owner = database;
    tableId = id;
    uniqueValueIndexTableId = valueIndexTableId;
    uniqueValueIndexState = valueIndexState;
    uniqueValueIndexColumn = indexColumn;
    columnCount = schema.columnCount();
    for (int index = 0; index < columnCount; index++) {
      writableColumn(index).set(schema.columnName(index));
    }
    schemaVersion = database.schemaVersion();
    available = true;
  }

  void set(
      RelationalDatabase database,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableSchema schema) {
    owner = database;
    tableId = id;
    uniqueValueIndexTableId = valueIndexTableId;
    uniqueValueIndexState = valueIndexState;
    uniqueValueIndexColumn = indexColumn;
    columnCount = schema.columnCount();
    for (int index = 0; index < columnCount; index++) {
      writableColumn(index).set(schema.columnName(index));
    }
    schemaVersion = database.schemaVersion();
    available = true;
  }

  void set(
      RelationalDatabase database,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      ByteBuffer source,
      int columnsOffset,
      int columns) {
    owner = database;
    tableId = id;
    uniqueValueIndexTableId = valueIndexTableId;
    uniqueValueIndexState = valueIndexState;
    uniqueValueIndexColumn = indexColumn;
    columnCount = columns;
    int offset = columnsOffset;
    for (int index = 0; index < columns; index++) {
      int length = source.getInt(offset);
      offset += Integer.BYTES;
      writableColumn(index).set(source, offset, length);
      offset += length;
    }
    schemaVersion = database.schemaVersion();
    available = true;
  }

  public int tableId() {
    return tableId;
  }

  public boolean isAvailable() {
    return available;
  }

  public boolean hasUniqueValueIndex() {
    return uniqueValueIndexTableId > 0 && uniqueValueIndexState == INDEX_READY;
  }

  public boolean hasBuildingUniqueValueIndex() {
    return uniqueValueIndexTableId > 0 && uniqueValueIndexState == INDEX_BUILDING;
  }

  public boolean hasUniqueIndexOn(int column) {
    return hasUniqueValueIndex() && uniqueValueIndexColumn == column;
  }

  public CharSequence keyColumnName() {
    return keyColumnName;
  }

  public CharSequence valueColumnName() {
    return valueColumnName;
  }

  public boolean matchesKeyColumn(CharSequence name) {
    return keyColumnName.matches(name);
  }

  public boolean matchesValueColumn(CharSequence name) {
    return valueColumnName.matches(name);
  }

  public int columnCount() {
    return columnCount;
  }

  public CharSequence columnName(int index) {
    return index >= 0 && index < columnCount ? writableColumn(index) : null;
  }

  public int findColumn(CharSequence name) {
    for (int index = 0; index < columnCount; index++) {
      if (writableColumn(index).matches(name)) {
        return index;
      }
    }
    return -1;
  }

  public int rowBytes() {
    return (columnCount - 1) * Long.BYTES;
  }

  int uniqueValueIndexTableId() {
    return uniqueValueIndexTableId;
  }

  int uniqueValueIndexState() {
    return uniqueValueIndexState;
  }

  int uniqueValueIndexColumn() {
    return uniqueValueIndexColumn;
  }

  boolean isOwnedBy(RelationalDatabase database) {
    return available
        && owner == database
        && schemaVersion == database.schemaVersion();
  }

  private ColumnName writableColumn(int index) {
    return index == 0
        ? keyColumnName : index == 1 ? valueColumnName : additionalColumns[index - 2];
  }

  private static final class ColumnName implements CharSequence {
    private final char[] characters = new char[TableSchema.MAXIMUM_NAME_LENGTH];
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

    void set(ByteBuffer source, int offset, int bytes) {
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
