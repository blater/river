package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Versioned catalog encoding; v1 remains readable and v2 persists index-build state. */
final class CatalogRecord {
  static final int MAXIMUM_BYTES = 96;

  private static final long SEQUENCE_MAGIC = 0x5249564552534551L; // RIVERSEQ
  private static final long TABLE_MAGIC = 0x524956455254424cL; // RIVERTBL
  private static final long INDEX_MAGIC = 0x5249564552494e44L; // RIVERIND
  private static final int VERSION_1 = 1;
  private static final int VERSION_2 = 2;

  private CatalogRecord() {
  }

  static void encodeSequence(ByteBuffer target, int nextTableId) {
    clear(target);
    target.putLong(0, SEQUENCE_MAGIC);
    target.putInt(8, VERSION_1);
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
        || scratch.getInt(8) != VERSION_1
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
        name);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name) {
    clear(target);
    target.putLong(0, TABLE_MAGIC);
    target.putInt(8, VERSION_2);
    target.putInt(12, tableId);
    target.putInt(16, name.length());
    target.putInt(20, uniqueValueIndexTableId);
    target.putInt(24, uniqueValueIndexState);
    for (int index = 0; index < name.length(); index++) {
      target.put(28 + index, (byte) name.charAt(index));
    }
    target.position(0);
    target.limit(28 + name.length());
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
    target.putInt(8, VERSION_2);
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
    int nameOffset = version == VERSION_1 ? 24 : 28;
    int nameBytes = source.length() >= 20 ? scratch.getInt(16) : -1;
    int valueIndexTableId = source.length() >= 24 ? scratch.getInt(20) : -1;
    int valueIndexState = version == VERSION_1
        ? valueIndexTableId == 0
            ? TableDefinition.INDEX_NONE : TableDefinition.INDEX_READY
        : source.length() >= 28 ? scratch.getInt(24) : -1;
    if (!status.isOk()
        || (version != VERSION_1 && version != VERSION_2)
        || source.length() < nameOffset + 1
        || source.length() != nameOffset + nameBytes
        || scratch.getLong(0) != TABLE_MAGIC
        || scratch.getInt(12) <= 0
        || scratch.getInt(12) > RelationalKey.MAXIMUM_TABLE_ID
        || valueIndexTableId < 0
        || valueIndexTableId > RelationalKey.MAXIMUM_TABLE_ID
        || !validIndexState(valueIndexTableId, valueIndexState)
        || nameBytes != expectedName.length()) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(nameOffset + index)) != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    result.set(database, scratch.getInt(12), valueIndexTableId, valueIndexState);
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
    int state = version == VERSION_1
        ? TableDefinition.INDEX_READY
        : source.length() >= 24 ? scratch.getInt(20) : -1;
    int nameOffset = version == VERSION_1 ? 24 : 28;
    int nameBytes = version == VERSION_1
        ? source.length() >= 24 ? scratch.getInt(20) : -1
        : source.length() >= 28 ? scratch.getInt(24) : -1;
    if (!status.isOk()
        || (version != VERSION_1 && version != VERSION_2)
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
