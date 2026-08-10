package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Fixed first catalog encoding; no durable Java object graph. */
final class CatalogRecord {
  static final int MAXIMUM_BYTES = 96;

  private static final long SEQUENCE_MAGIC = 0x5249564552534551L; // RIVERSEQ
  private static final long TABLE_MAGIC = 0x524956455254424cL; // RIVERTBL
  private static final int VERSION = 1;

  private CatalogRecord() {
  }

  static void encodeSequence(ByteBuffer target, int nextTableId) {
    clear(target);
    target.putLong(0, SEQUENCE_MAGIC);
    target.putInt(8, VERSION);
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
        || scratch.getInt(8) != VERSION
        || scratch.getInt(12) <= 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(scratch.getInt(12));
    return StatusCode.OK;
  }

  static void encodeTable(ByteBuffer target, int tableId, String name) {
    clear(target);
    target.putLong(0, TABLE_MAGIC);
    target.putInt(8, VERSION);
    target.putInt(12, tableId);
    target.putInt(16, name.length());
    target.putInt(20, 0);
    for (int index = 0; index < name.length(); index++) {
      target.put(24 + index, (byte) name.charAt(index));
    }
    target.position(0);
    target.limit(24 + name.length());
  }

  static StatusCode decodeTable(
      HeapRowResult source,
      ByteBuffer scratch,
      String expectedName,
      RelationalDatabase database,
      TableDefinition result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    int nameBytes = source.length() >= 20 ? scratch.getInt(16) : -1;
    if (!status.isOk()
        || source.length() < 25
        || source.length() != 24 + nameBytes
        || scratch.getLong(0) != TABLE_MAGIC
        || scratch.getInt(8) != VERSION
        || scratch.getInt(12) <= 0
        || scratch.getInt(12) > RelationalKey.MAXIMUM_TABLE_ID
        || nameBytes != expectedName.length()
        || scratch.getInt(20) != 0) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(24 + index)) != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    result.set(database, scratch.getInt(12));
    return StatusCode.OK;
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
}
