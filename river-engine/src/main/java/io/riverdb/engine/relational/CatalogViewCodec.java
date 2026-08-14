package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Durable encoding for one bounded catalog view definition. */
final class CatalogViewCodec {
  private static final long MAGIC = 0x5249564552564945L; // RIVERVIE
  private static final int VERSION = 1;
  private static final int HEADER_BYTES = 24;

  private CatalogViewCodec() {
  }

  static void encode(
      ByteBuffer target,
      CharSequence name,
      CharSequence query,
      int baseTableId) {
    clear(target);
    target.putLong(0, MAGIC);
    target.putInt(8, VERSION);
    target.putInt(12, name.length());
    target.putInt(16, query.length());
    target.putInt(20, baseTableId);
    int offset = HEADER_BYTES;
    for (int index = 0; index < name.length(); index++) {
      target.put(offset++, (byte) name.charAt(index));
    }
    for (int index = 0; index < query.length(); index++) {
      target.put(offset++, (byte) query.charAt(index));
    }
    target.position(0);
    target.limit(offset);
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
    for (int index = 0; index < queryBytes; index++) {
      result.append((char) Byte.toUnsignedInt(
          scratch.get(HEADER_BYTES + nameBytes + index)));
    }
    result.setBaseTableId(scratch.getInt(20));
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
        && scratch.getInt(20) > 0
        && scratch.getInt(20) <= RelationalKey.MAXIMUM_TABLE_ID
        && nameBytes > 0
        && nameBytes <= TableSchema.MAXIMUM_NAME_LENGTH
        && queryBytes > 0
        && queryBytes <= ViewDefinition.MAXIMUM_QUERY_LENGTH
        && source.length() == HEADER_BYTES + nameBytes + queryBytes;
  }

  private static StatusCode decodeCopied(
      ByteBuffer source,
      int nameBytes,
      int queryBytes,
      ViewDefinition result) {
    result.reset();
    for (int index = 0; index < queryBytes; index++) {
      result.append((char) Byte.toUnsignedInt(
          source.get(HEADER_BYTES + nameBytes + index)));
    }
    result.setBaseTableId(source.getInt(20));
    return StatusCode.OK;
  }

  private static void clear(ByteBuffer target) {
    target.clear();
    for (int index = 0; index < target.capacity(); index++) {
      target.put(index, (byte) 0);
    }
  }
}
