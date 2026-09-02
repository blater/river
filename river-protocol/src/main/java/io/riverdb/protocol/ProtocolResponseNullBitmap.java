package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Validates and decodes a response's canonical multiword null bitmap. */
final class ProtocolResponseNullBitmap {
  private ProtocolResponseNullBitmap() { }

  static int bytes(int columns) { return (columns + Byte.SIZE - 1) >>> 3; }

  static boolean validSize(int columns, int encodedBytes, int reserved) {
    return encodedBytes == bytes(columns) && reserved == 0;
  }

  static StatusCode decode(
      ByteBuffer source, int offset, int columns, int bytes, ProtocolResponse result) {
    if (!valid(source, offset, columns, bytes)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = result.beginNulls(columns);
    if (!status.isOk()) return status;
    int words = (columns + Long.SIZE - 1) >>> 6;
    for (int word = 0; word < words; word++) {
      if (!result.nullWordAt(word, word(source, offset, bytes, word))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return StatusCode.OK;
  }

  static StatusCode decodeNullable(
      ByteBuffer source, int offset, int columns, int bytes, ProtocolResponse result) {
    if (!valid(source, offset, columns, bytes) || bytes != bytes(columns)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.beginNullable(columns);
    int words = (columns + Long.SIZE - 1) >>> 6;
    for (int word = 0; word < words; word++) {
      if (!result.nullableWordAt(word, word(source, offset, bytes, word))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return StatusCode.OK;
  }

  private static boolean valid(ByteBuffer source, int offset, int columns, int bytes) {
    if (offset < 0 || bytes < 0 || offset > source.limit() - bytes) return false;
    int trailing = columns & 7;
    return bytes == 0 || trailing == 0
        || ((source.get(offset + bytes - 1) & 0xff) & ~((1 << trailing) - 1)) == 0;
  }

  private static long word(ByteBuffer source, int offset, int bytes, int word) {
    long value = 0;
    int first = word << 3;
    int count = Math.min(Long.BYTES, bytes - first);
    for (int index = 0; index < count; index++) {
      value |= (long) (source.get(offset + first + index) & 0xff) << (index << 3);
    }
    return value;
  }
}
