package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Immutable one-page row value addressed by a tuple leaf's page ID and generation. */
public final class TupleRowOverflowCodec {
  public static final int VERSION = 1;
  public static final int HEADER_BYTES = 32;
  public static final int MAX_VALUE_BYTES = PageCodec.MAX_PAYLOAD_BYTES - HEADER_BYTES;
  private static final long MAGIC = 0x52495654554f5646L; // RIVTUOVF

  private TupleRowOverflowCodec() { }

  public static StatusCode encode(
      ByteBuffer target, int start, long logicalRowId,
      ByteBuffer value, int valueOffset, int valueLength) {
    if (target == null || target.isReadOnly() || start < 0
        || target.limit() - start < PageCodec.MAX_PAYLOAD_BYTES
        || logicalRowId <= 0 || value == null || value == target
        || valueOffset < 0 || valueLength <= 0 || valueLength > MAX_VALUE_BYTES
        || valueOffset > value.limit() - valueLength) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < PageCodec.MAX_PAYLOAD_BYTES; index++) {
      target.put(start + index, (byte) 0);
    }
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, valueLength);
    FormatBytes.putLong(target, start + 16, logicalRowId);
    for (int index = 0; index < valueLength; index++) {
      target.put(start + HEADER_BYTES + index, value.get(valueOffset + index));
    }
    return StatusCode.OK;
  }

  public static StatusCode validate(
      ByteBuffer source, int start, long expectedLogicalRowId,
      TupleRowOverflowHeader result) {
    if (result == null || source == null || start < 0
        || source.limit() - start < PageCodec.MAX_PAYLOAD_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int length = FormatBytes.getInt(source, start + 12);
    long rowId = FormatBytes.getLong(source, start + 16);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || rowId <= 0 || expectedLogicalRowId > 0 && rowId != expectedLogicalRowId
        || length <= 0 || length > MAX_VALUE_BYTES
        || FormatBytes.getLong(source, start + 24) != 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(rowId, length);
    return StatusCode.OK;
  }
}
