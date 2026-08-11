package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Current catalog encoding for bounded relational schemas and index-build state. */
final class CatalogRecord {
  static final int MAXIMUM_BYTES =
      52 + TableSchema.MAXIMUM_COLUMNS * Long.BYTES
          + TableDefinition.MAXIMUM_INDEXES * 16
          + 64 + TableSchema.MAXIMUM_COLUMNS * (Integer.BYTES + 64);

  private static final long SEQUENCE_MAGIC = 0x5249564552534551L; // RIVERSEQ
  private static final long TABLE_MAGIC = 0x524956455254424cL; // RIVERTBL
  private static final long DROPPING_TABLE_MAGIC = 0x524956455244524fL; // RIVERDRO
  private static final long INDEX_MAGIC = 0x5249564552494e44L; // RIVERIND
  private static final int SEQUENCE_VERSION = 1;
  private static final int TABLE_VERSION = 6;
  private static final int INDEX_VERSION = 2;
  private static final int TABLE_DEFAULTS_OFFSET = 52;
  private static final int TABLE_INDEXES_OFFSET =
      TABLE_DEFAULTS_OFFSET + TableSchema.MAXIMUM_COLUMNS * Long.BYTES;

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
        2,
        1L,
        0,
        0,
        null,
        null,
        true);
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
        schema.columnCount(),
        schema.notNullMask(),
        schema.defaultMask(),
        schema.varcharMask(),
        schema,
        null,
        true);
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
    encodeTable(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema,
        true);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema,
      boolean unique) {
    int offset = encodeTableHeader(
        target,
        tableId,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        name,
        schema.columnCount(),
        schema.notNullMask(),
        schema.defaultMask(),
        schema.varcharMask(),
        null,
        schema,
        unique);
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
        name,
        true);
  }

  static void encodeIndex(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      CharSequence name) {
    encodeIndex(target, tableId, indexTableId, indexState, name, true);
  }

  static void encodeIndex(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      CharSequence name,
      boolean unique) {
    clear(target);
    target.putLong(0, INDEX_MAGIC);
    target.putInt(8, INDEX_VERSION);
    target.putInt(12, tableId);
    target.putInt(16, indexTableId);
    target.putInt(20, indexState);
    target.putInt(24, unique ? 1 : 0);
    target.putInt(28, name.length());
    for (int index = 0; index < name.length(); index++) {
      target.put(32 + index, (byte) name.charAt(index));
    }
    target.position(0);
    target.limit(32 + name.length());
  }

  static StatusCode decodeTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalDatabase database,
      TableDefinition result) {
    return decodeTable(
        source, scratch, expectedName, database, result, TABLE_MAGIC);
  }

  static StatusCode decodeDroppingTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalDatabase database,
      TableDefinition result) {
    return decodeTable(
        source, scratch, expectedName, database, result, DROPPING_TABLE_MAGIC);
  }

  static boolean isDroppingTable(HeapRowResult source, ByteBuffer scratch) {
    scratch.clear();
    return source.copyTo(scratch).isOk()
        && source.length() >= Long.BYTES
        && scratch.getLong(0) == DROPPING_TABLE_MAGIC;
  }

  static void encodeDroppingTable(
      ByteBuffer target,
      int tableId,
      CharSequence name,
      TableDefinition schema) {
    encodeTable(
        target,
        tableId,
        0,
        TableDefinition.INDEX_NONE,
        -1,
        name,
        schema);
    target.putLong(0, DROPPING_TABLE_MAGIC);
  }

  private static StatusCode decodeTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalDatabase database,
      TableDefinition result,
      long expectedMagic) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    int version = source.length() >= 12 ? scratch.getInt(8) : -1;
    int nameBytes = source.length() >= 20 ? scratch.getInt(16) : -1;
    int columnCount = source.length() >= 24 ? scratch.getInt(20) : -1;
    int indexCount = source.length() >= 28 ? scratch.getInt(24) : -1;
    long notNullMask = source.length() >= TABLE_INDEXES_OFFSET
        ? scratch.getLong(28) : -1;
    long defaultMask = source.length() >= TABLE_INDEXES_OFFSET
        ? scratch.getLong(36) : -1;
    long varcharMask = source.length() >= TABLE_INDEXES_OFFSET
        ? scratch.getLong(44) : -1;
    boolean validIndexCount = indexCount >= 0
        && indexCount <= TableDefinition.MAXIMUM_INDEXES;
    int nameOffset = validIndexCount
        ? TABLE_INDEXES_OFFSET + indexCount * 16 : -1;
    int columnsOffset = nameOffset < 0 ? -1 : nameOffset + nameBytes;
    int expectedBytes = columnsOffset;
    if (validIndexCount
        && nameBytes > 0
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
        || !validIndexCount
        || nameOffset < 0
        || source.length() < nameOffset + 1
        || source.length() != expectedBytes
        || scratch.getLong(0) != expectedMagic
        || scratch.getInt(12) <= 0
        || scratch.getInt(12) > RelationalKey.MAXIMUM_TABLE_ID
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || columnCount < 2
        || columnCount > TableSchema.MAXIMUM_COLUMNS
        || (notNullMask & 1) == 0
        || (notNullMask & ~((1L << columnCount) - 1)) != 0
        || (defaultMask & 1) != 0
        || (defaultMask & ~((1L << columnCount) - 1)) != 0
        || (varcharMask & 1) != 0
        || (varcharMask & ~((1L << columnCount) - 1)) != 0
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
        0,
        TableDefinition.INDEX_NONE,
        -1,
        scratch,
        columnsOffset,
        columnCount,
        notNullMask,
        defaultMask,
        varcharMask,
        TABLE_DEFAULTS_OFFSET);
    int buildingIndexes = 0;
    for (int index = 0; status.isOk() && index < indexCount; index++) {
      int offset = TABLE_INDEXES_OFFSET + index * 16;
      int indexTableId = scratch.getInt(offset);
      int indexState = scratch.getInt(offset + 4);
      int indexColumn = scratch.getInt(offset + 8);
      int unique = scratch.getInt(offset + 12);
      if (indexTableId <= 0
          || indexTableId > RelationalKey.MAXIMUM_TABLE_ID
          || indexTableId == scratch.getInt(12)
          || (indexState != TableDefinition.INDEX_BUILDING
              && indexState != TableDefinition.INDEX_READY
              && indexState != TableDefinition.INDEX_DROPPING)
          || indexColumn <= 0
          || indexColumn >= columnCount
          || (unique != 0 && unique != 1)
          || duplicateIndex(scratch, index, indexTableId, indexColumn)
          || (indexState == TableDefinition.INDEX_BUILDING && ++buildingIndexes > 1)) {
        status = StatusCode.CORRUPTION;
      } else {
        status = result.upsertIndex(
            indexTableId, indexState, indexColumn, unique == 1);
        if (status == StatusCode.CONFLICT
            || status == StatusCode.RESOURCE_EXHAUSTED
            || status == StatusCode.INVALID_EXTERNAL_INPUT) {
          status = StatusCode.CORRUPTION;
        }
      }
    }
    if (!status.isOk() || !validColumns(result)) {
      result.reset();
      return status.isOk() ? StatusCode.CORRUPTION : status;
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
    int unique = source.length() >= 28 ? scratch.getInt(24) : -1;
    int nameOffset = 32;
    int nameBytes = source.length() >= 32 ? scratch.getInt(28) : -1;
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
            && state != TableDefinition.INDEX_READY
            && state != TableDefinition.INDEX_DROPPING)
        || (unique != 0 && unique != 1)
        || nameBytes != expectedName.length()) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(nameOffset + index)) != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    result.set(scratch.getInt(12), scratch.getInt(16), state, unique == 1);
    return StatusCode.OK;
  }

  static StatusCode decodeIndexForTable(
      HeapRowResult source,
      ByteBuffer scratch,
      int expectedTableId,
      IndexResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    if (source.length() < 32 || scratch.getLong(0) != INDEX_MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = scratch.getInt(28);
    int state = scratch.getInt(20);
    int unique = scratch.getInt(24);
    if (scratch.getInt(8) != INDEX_VERSION
        || source.length() != 32 + nameBytes
        || scratch.getInt(12) <= 0
        || scratch.getInt(12) > RelationalKey.MAXIMUM_TABLE_ID
        || scratch.getInt(16) <= 0
        || scratch.getInt(16) > RelationalKey.MAXIMUM_TABLE_ID
        || (state != TableDefinition.INDEX_BUILDING
            && state != TableDefinition.INDEX_READY
            && state != TableDefinition.INDEX_DROPPING)
        || (unique != 0 && unique != 1)
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH) {
      return StatusCode.CORRUPTION;
    }
    if (scratch.getInt(12) != expectedTableId) {
      return StatusCode.CONFLICT;
    }
    result.set(scratch.getInt(12), scratch.getInt(16), state, unique == 1);
    return StatusCode.OK;
  }

  private static int encodeTableHeader(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      int indexColumn,
      CharSequence name,
      int columnCount,
      long notNullMask,
      long defaultMask,
      long varcharMask,
      TableSchema definition,
      TableDefinition existing,
      boolean unique) {
    clear(target);
    int existingCount = existing == null ? 0 : existing.uniqueIndexCount();
    int overrideSlot = -1;
    if (indexTableId > 0 && existing != null) {
      for (int index = 0; index < existingCount; index++) {
        if (existing.uniqueIndexTableId(index) == indexTableId
            || existing.uniqueIndexColumn(index) == indexColumn) {
          overrideSlot = index;
          break;
        }
      }
    }
    int indexCount = existingCount
        + (indexTableId > 0 && overrideSlot < 0 ? 1 : 0);
    if (existing == null && indexTableId > 0) {
      indexCount = 1;
    }
    target.putLong(0, TABLE_MAGIC);
    target.putInt(8, TABLE_VERSION);
    target.putInt(12, tableId);
    target.putInt(16, name.length());
    target.putInt(20, columnCount);
    target.putInt(24, indexCount);
    target.putLong(28, notNullMask);
    target.putLong(36, defaultMask);
    target.putLong(44, varcharMask);
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      long value = definition != null
          ? definition.defaultValue(index)
          : existing != null ? existing.defaultValue(index) : 0;
      target.putLong(TABLE_DEFAULTS_OFFSET + index * Long.BYTES, value);
    }
    for (int index = 0; index < indexCount; index++) {
      int output = TABLE_INDEXES_OFFSET + index * 16;
      boolean override = index == overrideSlot
          || existing == null && index == 0
          || existing != null && index == existingCount;
      target.putInt(
          output,
          override ? indexTableId : existing.uniqueIndexTableId(index));
      target.putInt(
          output + 4,
          override ? indexState : existing.uniqueIndexState(index));
      target.putInt(
          output + 8,
          override ? indexColumn : existing.uniqueIndexColumn(index));
      target.putInt(
          output + 12,
          (override ? unique : existing.indexIsUnique(index)) ? 1 : 0);
    }
    int nameOffset = TABLE_INDEXES_OFFSET + indexCount * 16;
    for (int index = 0; index < name.length(); index++) {
      target.put(nameOffset + index, (byte) name.charAt(index));
    }
    return nameOffset + name.length();
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

  private static boolean duplicateIndex(
      ByteBuffer source,
      int slot,
      int tableId,
      int column) {
    for (int prior = 0; prior < slot; prior++) {
      int offset = TABLE_INDEXES_OFFSET + prior * 16;
      if (source.getInt(offset) == tableId || source.getInt(offset + 8) == column) {
        return true;
      }
    }
    return false;
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
    private boolean unique;

    void set(int ownerTableId, int storageTableId, int indexState, boolean isUnique) {
      tableId = ownerTableId;
      indexTableId = storageTableId;
      state = indexState;
      unique = isUnique;
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

    boolean isUnique() {
      return unique;
    }
  }
}
