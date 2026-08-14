package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Durable encoding for one catalog value-index record. */
final class CatalogIndexCodec {
  private static final long MAGIC = 0x5249564552494e44L; // RIVERIND
  private static final int VERSION = 3;
  private static final int HEADER_BYTES = 32;

  private CatalogIndexCodec() {
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      CharSequence name) {
    encode(
        target,
        tableId,
        indexTableId,
        TableDefinition.INDEX_READY,
        name,
        true,
        false);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      CharSequence name) {
    encode(target, tableId, indexTableId, indexState, name, true, false);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      CharSequence name,
      boolean unique) {
    encode(target, tableId, indexTableId, indexState, name, unique, false);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      CharSequence name,
      boolean unique,
      boolean constraint) {
    clear(target);
    target.putLong(0, MAGIC);
    target.putInt(8, VERSION);
    target.putInt(12, tableId);
    target.putInt(16, indexTableId);
    target.putInt(20, indexState);
    target.putInt(24, (unique ? 1 : 0) | (constraint ? 2 : 0));
    target.putInt(28, name.length());
    for (int index = 0; index < name.length(); index++) {
      target.put(HEADER_BYTES + index, (byte) name.charAt(index));
    }
    target.position(0);
    target.limit(HEADER_BYTES + name.length());
  }

  static StatusCode decode(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      Result result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    int version = source.length() >= 12 ? scratch.getInt(8) : -1;
    int state = source.length() >= 24 ? scratch.getInt(20) : -1;
    int flags = source.length() >= 28 ? scratch.getInt(24) : -1;
    int nameBytes = source.length() >= HEADER_BYTES ? scratch.getInt(28) : -1;
    if (!status.isOk()
        || version != VERSION
        || source.length() < HEADER_BYTES + 1
        || source.length() != HEADER_BYTES + nameBytes
        || scratch.getLong(0) != MAGIC
        || !validTableId(scratch.getInt(12))
        || !validTableId(scratch.getInt(16))
        || !validState(state)
        || (flags & ~3) != 0
        || nameBytes != expectedName.length()) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(HEADER_BYTES + index))
          != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    result.set(
        scratch.getInt(12),
        scratch.getInt(16),
        state,
        (flags & 1) != 0,
        (flags & 2) != 0);
    return StatusCode.OK;
  }

  static StatusCode decodeForTable(
      HeapRowResult source,
      ByteBuffer scratch,
      int expectedTableId,
      Result result) {
    return decodeForTable(source, scratch, expectedTableId, null, result);
  }

  static StatusCode decodeForTable(
      HeapRowResult source,
      ByteBuffer scratch,
      int expectedTableId,
      TableSchema.ColumnName name,
      Result result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    if (source.length() < HEADER_BYTES || scratch.getLong(0) != MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = scratch.getInt(28);
    int state = scratch.getInt(20);
    int flags = scratch.getInt(24);
    if (scratch.getInt(8) != VERSION
        || source.length() != HEADER_BYTES + nameBytes
        || !validTableId(scratch.getInt(12))
        || !validTableId(scratch.getInt(16))
        || !validState(state)
        || (flags & ~3) != 0
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH) {
      return StatusCode.CORRUPTION;
    }
    if (scratch.getInt(12) != expectedTableId) {
      return StatusCode.CONFLICT;
    }
    if (name != null) {
      name.set(scratch, HEADER_BYTES, nameBytes);
      if (!RelationalKey.validName(name)) {
        return StatusCode.CORRUPTION;
      }
    }
    result.set(
        scratch.getInt(12),
        scratch.getInt(16),
        state,
        (flags & 1) != 0,
        (flags & 2) != 0);
    return StatusCode.OK;
  }

  static boolean matchesMagic(long magic) {
    return magic == MAGIC;
  }

  private static boolean validTableId(int tableId) {
    return tableId > 0 && tableId <= RelationalKey.MAXIMUM_TABLE_ID;
  }

  private static boolean validState(int state) {
    return state == TableDefinition.INDEX_BUILDING
        || state == TableDefinition.INDEX_READY
        || state == TableDefinition.INDEX_DROPPING;
  }

  private static void clear(ByteBuffer target) {
    target.clear();
    for (int index = 0; index < target.capacity(); index++) {
      target.put(index, (byte) 0);
    }
  }

  static final class Result {
    private int tableId;
    private int indexTableId;
    private int state;
    private boolean unique;
    private boolean constraint;

    void set(
        int ownerTableId,
        int storageTableId,
        int indexState,
        boolean isUnique,
        boolean isConstraint) {
      tableId = ownerTableId;
      indexTableId = storageTableId;
      state = indexState;
      unique = isUnique;
      constraint = isConstraint;
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

    boolean isConstraint() {
      return constraint;
    }
  }
}
