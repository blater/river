package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Caller-reused exact record mapping a logical row to paged row and key bytes. */
final class SqlBlockRowPagedIndexRecord {
  static final int BYTES = 48;
  static final int FLAG_KEY_PRESENT = 1;
  private static final int KNOWN_FLAGS = FLAG_KEY_PRESENT;

  private final ByteBuffer bytes = ByteBuffer.allocateDirect(BYTES).order(ByteOrder.BIG_ENDIAN);

  StatusCode encode(
      long rowOffset,
      int rowLength,
      long rowKey,
      long ordinal,
      long keyOffset,
      int keyLength,
      int flags) {
    if (rowOffset < 0 || rowLength <= 0 || ordinal < 0 || keyOffset < 0 || keyLength < 0
        || (flags & ~KNOWN_FLAGS) != 0
        || (keyLength == 0) != ((flags & FLAG_KEY_PRESENT) == 0)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bytes.clear();
    bytes.putLong(rowOffset);
    bytes.putLong(rowKey);
    bytes.putLong(ordinal);
    bytes.putLong(keyOffset);
    bytes.putInt(rowLength);
    bytes.putInt(keyLength);
    bytes.putInt(flags);
    bytes.putInt(0);
    bytes.flip();
    return StatusCode.OK;
  }

  StatusCode prepareRead() {
    bytes.clear();
    bytes.limit(BYTES);
    return StatusCode.OK;
  }

  StatusCode validate(long expectedOrdinal) {
    if (bytes.position() != BYTES) return StatusCode.CORRUPTION;
    bytes.flip();
    int flags = bytes.getInt(40);
    int keyLength = bytes.getInt(36);
    if (bytes.getLong(0) < 0 || bytes.getInt(32) <= 0
        || bytes.getLong(16) != expectedOrdinal || bytes.getLong(24) < 0
        || keyLength < 0 || (flags & ~KNOWN_FLAGS) != 0 || bytes.getInt(44) != 0
        || (keyLength == 0) != ((flags & FLAG_KEY_PRESENT) == 0)) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  StatusCode validateRowBounds(long rowBytes) {
    return range(rowOffset(), rowLength(), rowBytes)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  StatusCode validateKeyBounds(long keyBytes) {
    return keyPresent() && range(keyOffset(), keyLength(), keyBytes)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  ByteBuffer bytes() { return bytes; }
  long rowOffset() { return bytes.getLong(0); }
  long rowKey() { return bytes.getLong(8); }
  long ordinal() { return bytes.getLong(16); }
  long keyOffset() { return bytes.getLong(24); }
  int rowLength() { return bytes.getInt(32); }
  int keyLength() { return bytes.getInt(36); }
  boolean keyPresent() { return (bytes.getInt(40) & FLAG_KEY_PRESENT) != 0; }

  private static boolean range(long offset, int length, long total) {
    return offset >= 0 && length >= 0 && total >= 0
        && offset <= total && length <= total - offset;
  }
}
