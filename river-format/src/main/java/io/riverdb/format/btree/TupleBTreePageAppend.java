package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Ordered append into one initialized variable-key B-tree payload. */
final class TupleBTreePageAppend {
  private TupleBTreePageAppend() { }

  static StatusCode append(
      ByteBuffer page, int start, TupleShape shape, int expectedType,
      ByteBuffer key, int keyOffset, int keyLength, int rightChildPageId) {
    if (!TupleBTreePageBytes.validPayload(page, start, true)
        || key == null || keyOffset < 0 || keyLength <= 0
        || key.limit() - keyOffset < keyLength
        || !TupleKeyCodec.matchesPhysicalIndexKey(key, keyOffset, keyLength, shape)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int count = FormatBytes.getInt(page, start + 16);
    int freeStart = FormatBytes.getInt(page, start + 28);
    int freeEnd = FormatBytes.getInt(page, start + 32);
    int highOffset = FormatBytes.getInt(page, start + 36);
    int highLength = FormatBytes.getInt(page, start + 40);
    if (!validHeader(page, start, shape, expectedType, rightChildPageId, count, freeStart)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (count > 0 && comparePrevious(
        page, start, count, key, keyOffset, keyLength) >= 0) return StatusCode.CONFLICT;
    if (highLength > 0 && TupleKeyCodec.compare(
        key, keyOffset, keyLength, page, start + highOffset, highLength) >= 0) {
      return StatusCode.CONFLICT;
    }
    int newFreeStart = freeStart + TupleBTreePageCodec.SLOT_BYTES;
    int newFreeEnd = freeEnd - keyLength;
    if (newFreeStart > newFreeEnd) return StatusCode.RESOURCE_EXHAUSTED;
    TupleBTreePageBytes.copy(key, keyOffset, page, start + newFreeEnd, keyLength);
    int slot = start + freeStart;
    FormatBytes.putInt(page, slot, newFreeEnd);
    FormatBytes.putInt(page, slot + 4, keyLength);
    FormatBytes.putInt(page, slot + 8, rightChildPageId);
    FormatBytes.putInt(page, start + 16, count + 1);
    FormatBytes.putInt(page, start + 28, newFreeStart);
    FormatBytes.putInt(page, start + 32, newFreeEnd);
    return StatusCode.OK;
  }

  private static boolean validHeader(
      ByteBuffer page, int start, TupleShape shape,
      int expectedType, int rightChild, int count, int freeStart) {
    return FormatBytes.getLong(page, start) == TupleBTreePageCodec.MAGIC
        && FormatBytes.getInt(page, start + 8) == TupleBTreePageCodec.VERSION
        && FormatBytes.getInt(page, start + 12) == expectedType
        && count >= 0 && count < TupleBTreePageCodec.MAXIMUM_SLOTS
        && freeStart == TupleBTreePageCodec.HEADER_BYTES
            + count * TupleBTreePageCodec.SLOT_BYTES
        && shape != null && FormatBytes.getInt(page, start + 44) == shape.partCount()
        && FormatBytes.getLong(page, start + 48) == shape.descriptorHash()
        && (expectedType != TupleBTreePageCodec.TYPE_INTERNAL || rightChild > 0);
  }

  private static int comparePrevious(
      ByteBuffer page, int start, int count,
      ByteBuffer key, int keyOffset, int keyLength) {
    int slot = start + TupleBTreePageCodec.HEADER_BYTES
        + (count - 1) * TupleBTreePageCodec.SLOT_BYTES;
    int offset = FormatBytes.getInt(page, slot);
    int length = FormatBytes.getInt(page, slot + 4);
    return TupleKeyCodec.compare(page, start + offset, length, key, keyOffset, keyLength);
  }
}
