package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Current catalog encoding for bounded BIGINT schemas and index-build state. */
final class CatalogRecord {
  static final int MAXIMUM_BYTES =
      36 + 64 + TableSchema.MAXIMUM_COLUMNS * (Integer.BYTES + 64);

  private static final long SEQUENCE_MAGIC = 0x5249564552534551L; // RIVERSEQ
  private static final long TABLE_MAGIC = 0x524956455254424cL; // RIVERTBL
  private static final long INDEX_MAGIC = 0x5249564552494e44L; // RIVERIND
  private static final int SEQUENCE_VERSION = 1;
  private static final int TABLE_VERSION = 1;
  private static final int INDEX_VERSION = 1;

  private CatalogRecord() {
  }

  static void encodeSequence(ByteBuffer target, int nextTableId) {
    clear(target);
    target.putLong(0, SEQUENCE_MAGIC);
    target.putInt(8, SEQUENCE_VERSION);
    target.putInt(12, nextTableId);
    target.position(0);
    target.limit(16);
  }

  static StatusCode decodeSequence(
      HeapRowResult source,
      ByteBuffer scratch,
      IntResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()
        || source.length() != 16
        || scratch.getLong(0) != SEQUENCE_MAGIC
        || scratch.getInt(8) != SEQUENCE_VERSION
        || scratch.getInt(12) <= 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(scratch.getInt(12));
    return StatusCode.OK;
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      CharSequence name) {
    encodeTable(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexTableId == 0
            ? TableDefinition.INDEX_NONE : TableDefinition.INDEX_READY,
        name,
        "key",
        "value");
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name) {
    encodeTable(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        name,
        "key",
        "value");
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName) {
    int indexColumn = uniqueValueIndexTableId == 0 ? -1 : 1;
    int offset = encodeTableHeader(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        2);
    offset = encodeColumn(target, offset, keyColumnName);
    offset = encodeColumn(target, offset, valueColumnName);
    target.position(0);
    target.limit(offset);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableSchema schema) {
    int offset = encodeTableHeader(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema.columnCount());
    for (int index = 0; index < schema.columnCount(); index++) {
      offset = encodeColumn(target, offset, schema.columnName(index));
    }
    target.position(0);
    target.limit(offset);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema) {
    int offset = encodeTableHeader(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema.columnCount());
    for (int index = 0; index < schema.columnCount(); index++) {
      offset = encodeColumn(target, offset, schema.columnName(index));
    }
    target.position(0);
    target.limit(offset);
  }

  static void encodeIndex(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      CharSequence name) {
    encodeIndex(
        target,
        tableId,
        indexTableId,
        TableDefinition.INDEX_READY,
        name);
  }

  static void encodeIndex(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      CharSequence name) {
    clear(target);
    target.putLong(0, INDEX_MAGIC);
    target.putInt(8, INDEX_VERSION);
    target.putInt(12, tableId);
    target.putInt(16, indexTableId);
    target.putInt(20, indexState);
    target.putInt(24, name.length());
    for (int index = 0; index < name.length(); index++) {
      target.put(28 + index, (byte) name.charAt(index));
    }
    target.position(0);
    target.limit(28 + name.length());
  }

  static StatusCode decodeTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalDatabase database,
      TableDefinition result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    int version = source.length() >= 12 ? scratch.getInt(8) : -1;
    int nameOffset = 36;
    int nameBytes = source.length() >= 20 ? scratch.getInt(16) : -1;
    int valueIndexTableId = source.length() >= 24 ? scratch.getInt(20) : -1;
    int valueIndexState = source.length() >= 28 ? scratch.getInt(24) : -1;
    int indexColumn = source.length() >= 32 ? scratch.getInt(28) : -2;
    int columnCount = source.length() >= 36 ? scratch.getInt(32) : -1;
    int columnsOffset = nameOffset + nameBytes;
    int expectedBytes = columnsOffset;
    if (nameBytes > 0
        && nameBytes <= TableSchema.MAXIMUM_NAME_LENGTH
        && columnsOffset >= nameOffset
        && columnsOffset <= source.length()
        && columnCount >= 2
        && columnCount <= TableSchema.MAXIMUM_COLUMNS) {
      for (int index = 0; index < columnCount; index++) {
        if (expectedBytes > source.length() - Integer.BYTES) {
          expectedBytes = -1;
          break;
        }
        int columnBytes = scratch.getInt(expectedBytes);
        if (columnBytes <= 0
            || columnBytes > TableSchema.MAXIMUM_NAME_LENGTH
            || expectedBytes > source.length() - Integer.BYTES - columnBytes) {
          expectedBytes = -1;
          break;
        }
        expectedBytes += Integer.BYTES + columnBytes;
      }
    }
    if (!status.isOk()
        || version != TABLE_VERSION
        || source.length() < nameOffset + 1
        || source.length() != expectedBytes
        || scratch.getLong(0) != TABLE_MAGIC
        || scratch.getInt(12) <= 0
        || scratch.getInt(12) > RelationalKey.MAXIMUM_TABLE_ID
        || valueIndexTableId < 0
        || valueIndexTableId > RelationalKey.MAXIMUM_TABLE_ID
        || !validIndexState(valueIndexTableId, valueIndexState)
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || columnCount < 2
        || columnCount > TableSchema.MAXIMUM_COLUMNS
        || (valueIndexTableId == 0 && indexColumn != -1)
        || (valueIndexTableId > 0
            && (indexColumn <= 0 || indexColumn >= columnCount))
        || nameBytes != expectedName.length()) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(nameOffset + index)) != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    result.set(
        database,
        scratch.getInt(12),
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        scratch,
        columnsOffset,
        columnCount);
    if (!validColumns(result)) {
      result.reset();
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static StatusCode decodeIndex(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      IndexResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    int version = source.length() >= 12 ? scratch.getInt(8) : -1;
    int state = source.length() >= 24 ? scratch.getInt(20) : -1;
    int nameOffset = 28;
    int nameBytes = source.length() >= 28 ? scratch.getInt(24) : -1;
    if (!status.isOk()
        || version != INDEX_VERSION
        || source.length() < nameOffset + 1
        || source.length() != nameOffset + nameBytes
        || scratch.getLong(0) != INDEX_MAGIC
        || scratch.getInt(12) <= 0
        || scratch.getInt(12) > RelationalKey.MAXIMUM_TABLE_ID
        || scratch.getInt(16) <= 0
        || scratch.getInt(16) > RelationalKey.MAXIMUM_TABLE_ID
        || (state != TableDefinition.INDEX_BUILDING
            && state != TableDefinition.INDEX_READY)
        || nameBytes != expectedName.length()) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(nameOffset + index)) != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    result.set(scratch.getInt(12), scratch.getInt(16), state);
    return StatusCode.OK;
  }

  private static boolean validIndexState(int indexTableId, int state) {
    return indexTableId == 0 && state == TableDefinition.INDEX_NONE
        || indexTableId > 0
            && (state == TableDefinition.INDEX_BUILDING
                || state == TableDefinition.INDEX_READY);
  }

  private static int encodeTableHeader(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      int indexColumn,
      CharSequence name,
      int columnCount) {
    clear(target);
    target.putLong(0, TABLE_MAGIC);
    target.putInt(8, TABLE_VERSION);
    target.putInt(12, tableId);
    target.putInt(16, name.length());
    target.putInt(20, indexTableId);
    target.putInt(24, indexState);
    target.putInt(28, indexColumn);
    target.putInt(32, columnCount);
    for (int index = 0; index < name.length(); index++) {
      target.put(36 + index, (byte) name.charAt(index));
    }
    return 36 + name.length();
  }

  private static int encodeColumn(ByteBuffer target, int offset, CharSequence name) {
    target.putInt(offset, name.length());
    offset += Integer.BYTES;
    for (int index = 0; index < name.length(); index++) {
      target.put(offset + index, (byte) name.charAt(index));
    }
    return offset + name.length();
  }

  private static boolean validColumns(TableDefinition table) {
    for (int index = 0; index < table.columnCount(); index++) {
      CharSequence name = table.columnName(index);
      if (!RelationalKey.validName(name)) {
        return false;
      }
      for (int prior = 0; prior < index; prior++) {
        if (sameName(name, table.columnName(prior))) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean sameName(CharSequence first, CharSequence second) {
    if (first.length() == second.length()) {
      for (int index = 0; index < first.length(); index++) {
        if (first.charAt(index) != second.charAt(index)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  private static void clear(ByteBuffer target) {
    target.clear();
    for (int index = 0; index < target.capacity(); index++) {
      target.put(index, (byte) 0);
    }
  }

  static final class IntResult {
    private int value;

    void set(int result) {
      value = result;
    }

    int value() {
      return value;
    }
  }

  static final class IndexResult {
    private int tableId;
    private int indexTableId;
    private int state;

    void set(int ownerTableId, int storageTableId, int indexState) {
      tableId = ownerTableId;
      indexTableId = storageTableId;
      state = indexState;
    }

    int tableId() {
      return tableId;
    }

    int indexTableId() {
      return indexTableId;
    }

    int state() {
      return state;
    }
  }
}
