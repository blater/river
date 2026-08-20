package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Durable encoding for one bounded catalog view definition. */
final class CatalogViewCodec {
  private static final long MAGIC = 0x5249564552564945L; // RIVERVIE
  private static final int VERSION = 3;
  private static final int HEADER_BYTES = 32;

  private CatalogViewCodec() {
  }

  static StatusCode encode(
      ByteBuffer target,
      CharSequence name,
      CharSequence query,
      int baseTableId,
      int joinTableId) {
    int queryBytes = Utf8Text.encodedLength(query);
    if (target == null
        || name == null
        || name.length() <= 0
        || name.length() > TableSchema.MAXIMUM_NAME_LENGTH
        || query == null
        || query.length() <= 0
        || query.length() > ViewDefinition.MAXIMUM_QUERY_LENGTH
        || queryBytes <= 0
        || !validEncodedLineage(baseTableId, joinTableId)
        || HEADER_BYTES + name.length() > target.capacity() - queryBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    clear(target);
    target.putLong(0, MAGIC);
    target.putInt(8, VERSION);
    target.putInt(12, name.length());
    target.putInt(16, queryBytes);
    target.putInt(20, joinTableId == 0 ? 1 : 2);
    target.putInt(24, baseTableId);
    target.putInt(28, joinTableId);
    int offset = HEADER_BYTES;
    for (int index = 0; index < name.length(); index++) {
      target.put(offset++, (byte) name.charAt(index));
    }
    target.position(offset);
    if (Utf8Text.encode(query, target) != queryBytes) {
      clear(target);
      return StatusCode.INVARIANT_BROKEN;
    }
    offset = target.position();
    target.position(0);
    target.limit(offset);
    return StatusCode.OK;
  }

  static StatusCode decode(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      ViewDefinition result) {
    result.reset();
    StatusCode status = copy(source, scratch);
    if (!status.isOk()) {
      return status;
    }
    if (source.length() < Long.BYTES) {
      return StatusCode.CORRUPTION;
    }
    if (scratch.getLong(0) != MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = source.length() >= 20 ? scratch.getInt(12) : -1;
    int queryBytes = source.length() >= 20 ? scratch.getInt(16) : -1;
    if (!validHeader(source, scratch, nameBytes, queryBytes)
        || expectedName.length() != nameBytes) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(HEADER_BYTES + index))
          != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    status = result.setUtf8(scratch, HEADER_BYTES + nameBytes, queryBytes);
    if (!status.isOk()) return status;
    result.setLineage(
        scratch.getInt(20), scratch.getInt(24), scratch.getInt(28));
    return StatusCode.OK;
  }

  static StatusCode decodeForScan(
      HeapRowResult source,
      ByteBuffer scratch,
      TableSchema.ColumnName name,
      ViewDefinition result) {
    StatusCode status = copy(source, scratch);
    if (!status.isOk()) {
      return status;
    }
    if (source.length() < Long.BYTES || scratch.getLong(0) != MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = source.length() >= 20 ? scratch.getInt(12) : -1;
    int queryBytes = source.length() >= 20 ? scratch.getInt(16) : -1;
    if (!validHeader(source, scratch, nameBytes, queryBytes)) {
      return StatusCode.CORRUPTION;
    }
    name.set(scratch, HEADER_BYTES, nameBytes);
    if (!RelationalKey.validName(name)) {
      return StatusCode.CORRUPTION;
    }
    return decodeCopied(scratch, nameBytes, queryBytes, result);
  }

  static boolean matchesMagic(long magic) {
    return magic == MAGIC;
  }

  private static StatusCode copy(HeapRowResult source, ByteBuffer scratch) {
    scratch.clear();
    return source.copyTo(scratch);
  }

  private static boolean validHeader(
      HeapRowResult source,
      ByteBuffer scratch,
      int nameBytes,
      int queryBytes) {
    return source.length() >= HEADER_BYTES + 1
        && scratch.getInt(8) == VERSION
        && validDecodedLineage(scratch)
        && nameBytes > 0
        && nameBytes <= TableSchema.MAXIMUM_NAME_LENGTH
        && queryBytes > 0
        && queryBytes <= ViewDefinition.MAXIMUM_QUERY_LENGTH * 4
        && source.length() == HEADER_BYTES + nameBytes + queryBytes;
  }

  private static StatusCode decodeCopied(
      ByteBuffer source,
      int nameBytes,
      int queryBytes,
      ViewDefinition result) {
    StatusCode status = result.setUtf8(
        source, HEADER_BYTES + nameBytes, queryBytes);
    if (!status.isOk()) return status;
    result.setLineage(source.getInt(20), source.getInt(24), source.getInt(28));
    return StatusCode.OK;
  }

  private static boolean validEncodedLineage(int baseTableId, int joinTableId) {
    return validTableId(baseTableId)
        && (joinTableId == 0
            || validTableId(joinTableId) && joinTableId != baseTableId);
  }

  private static boolean validDecodedLineage(ByteBuffer source) {
    int count = source.getInt(20);
    int baseTableId = source.getInt(24);
    int joinTableId = source.getInt(28);
    return validTableId(baseTableId)
        && (count == 1 && joinTableId == 0
            || count == 2
                && validTableId(joinTableId)
                && joinTableId != baseTableId);
  }

  private static boolean validTableId(int tableId) {
    return tableId > 0 && tableId <= RelationalKey.MAXIMUM_TABLE_ID;
  }

  private static void clear(ByteBuffer target) {
    target.clear();
    for (int index = 0; index < target.capacity(); index++) {
      target.put(index, (byte) 0);
    }
  }
}
