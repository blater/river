package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Caller-owned resolved logical table identity. */
public final class TableDefinition {
  private static final int MAXIMUM_COLUMN_NAME_LENGTH = 64;
  static final int INDEX_NONE = 0;
  static final int INDEX_BUILDING = 1;
  static final int INDEX_READY = 2;

  private RelationalDatabase owner;
  private int tableId;
  private int uniqueValueIndexTableId;
  private int uniqueValueIndexState;
  private final ColumnName keyColumnName = new ColumnName();
  private final ColumnName valueColumnName = new ColumnName();
  private long schemaVersion;
  private boolean available;

  public void reset() {
    owner = null;
    tableId = 0;
    uniqueValueIndexTableId = 0;
    uniqueValueIndexState = INDEX_NONE;
    keyColumnName.reset();
    valueColumnName.reset();
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
    keyColumnName.set(keyName);
    valueColumnName.set(valueName);
    schemaVersion = database.schemaVersion();
    available = true;
  }

  void set(
      RelationalDatabase database,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      ByteBuffer source,
      int keyOffset,
      int keyLength,
      int valueOffset,
      int valueLength) {
    owner = database;
    tableId = id;
    uniqueValueIndexTableId = valueIndexTableId;
    uniqueValueIndexState = valueIndexState;
    keyColumnName.set(source, keyOffset, keyLength);
    valueColumnName.set(source, valueOffset, valueLength);
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

  int uniqueValueIndexTableId() {
    return uniqueValueIndexTableId;
  }

  int uniqueValueIndexState() {
    return uniqueValueIndexState;
  }

  boolean isOwnedBy(RelationalDatabase database) {
    return available
        && owner == database
        && schemaVersion == database.schemaVersion();
  }

  private static final class ColumnName implements CharSequence {
    private final char[] characters = new char[MAXIMUM_COLUMN_NAME_LENGTH];
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
