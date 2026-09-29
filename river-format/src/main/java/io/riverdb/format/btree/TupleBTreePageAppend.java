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
    return append(page, start, shape, expectedType, key, keyOffset, keyLength,
        null, 0, 0, 0, 0, 0, rightChildPageId);
  }

  static StatusCode appendLeaf(
      ByteBuffer page, int start, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      ByteBuffer value, int valueOffset, int valueLength,
      int overflowPageId, long overflowGeneration, long modificationSequence) {
    return append(page, start, shape, TupleBTreePageCodec.TYPE_LEAF,
        key, keyOffset, keyLength, value, valueOffset, valueLength,
        overflowPageId, overflowGeneration, modificationSequence, 0);
  }

  private static StatusCode append(
      ByteBuffer page, int start, TupleShape shape, int expectedType,
      ByteBuffer key, int keyOffset, int keyLength,
      ByteBuffer value, int valueOffset, int valueLength,
      int overflowPageId, long overflowGeneration, long modificationSequence,
      int rightChildPageId) {
    if (!TupleBTreePageBytes.validPayload(page, start, true)
        || key == null || keyOffset < 0 || keyLength <= 0
        || key.limit() - keyOffset < keyLength
        || !validValue(expectedType, value, valueOffset, valueLength,
            overflowPageId, overflowGeneration, modificationSequence)
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
    int slotBytes = TupleBTreePageCodec.slotBytes(expectedType);
    int newFreeStart = freeStart + slotBytes;
    int inlineLength = overflowPageId == 0 ? valueLength : 0;
    int newFreeEnd = freeEnd - keyLength - inlineLength;
    if (newFreeStart > newFreeEnd) return StatusCode.RESOURCE_EXHAUSTED;
    TupleBTreePageBytes.copy(key, keyOffset, page, start + newFreeEnd, keyLength);
    if (inlineLength > 0) {
      TupleBTreePageBytes.copy(
          value, valueOffset, page, start + newFreeEnd + keyLength, inlineLength);
    }
    int slot = start + freeStart;
    FormatBytes.putInt(page, slot, newFreeEnd);
    FormatBytes.putInt(page, slot + 4, keyLength);
    FormatBytes.putInt(page, slot + 8, rightChildPageId);
    if (expectedType == TupleBTreePageCodec.TYPE_LEAF) {
      FormatBytes.putInt(page, slot + 8, inlineLength == 0 ? 0 : newFreeEnd + keyLength);
      FormatBytes.putInt(page, slot + 12, valueLength);
      FormatBytes.putInt(page, slot + 16, overflowPageId);
      FormatBytes.putLong(page, slot + 20, overflowGeneration);
      FormatBytes.putLong(page, slot + 28, modificationSequence);
      FormatBytes.putInt(page, slot + 36, 0);
    }
    FormatBytes.putInt(page, start + 16, count + 1);
    FormatBytes.putInt(page, start + 28, newFreeStart);
    FormatBytes.putInt(page, start + 32, newFreeEnd);
    return StatusCode.OK;
  }

  private static boolean validValue(
      int type, ByteBuffer value, int offset, int length,
      int overflowPageId, long overflowGeneration, long modificationSequence) {
    if (type == TupleBTreePageCodec.TYPE_INTERNAL) {
      return value == null && length == 0 && overflowPageId == 0
          && overflowGeneration == 0 && modificationSequence == 0;
    }
    if (type != TupleBTreePageCodec.TYPE_LEAF) return false;
    return validLeafValueInput(
        value, offset, length, overflowPageId, overflowGeneration, modificationSequence);
  }

  static boolean validLeafValueInput(
      ByteBuffer value, int offset, int length,
      int overflowPageId, long overflowGeneration, long modificationSequence) {
    if (length < 0 || length > io.riverdb.format.page.PageCodec.MAX_PAYLOAD_BYTES
        || modificationSequence < 0) return false;
    if (overflowPageId > 0) {
      return value == null && length > 0 && overflowGeneration > 0;
    }
    return overflowPageId == 0 && overflowGeneration == 0
        && (length == 0 && value == null
            || length > 0 && value != null && offset >= 0
                && value.limit() - offset >= length);
  }

  private static boolean validHeader(
      ByteBuffer page, int start, TupleShape shape,
      int expectedType, int rightChild, int count, int freeStart) {
    return FormatBytes.getLong(page, start) == TupleBTreePageCodec.MAGIC
        && FormatBytes.getInt(page, start + 8) == TupleBTreePageCodec.VERSION
        && FormatBytes.getInt(page, start + 12) == expectedType
        && count >= 0 && count < (expectedType == TupleBTreePageCodec.TYPE_LEAF
            ? TupleBTreePageCodec.MAXIMUM_LEAF_SLOTS : TupleBTreePageCodec.MAXIMUM_SLOTS)
        && freeStart == TupleBTreePageCodec.HEADER_BYTES
            + count * TupleBTreePageCodec.slotBytes(expectedType)
        && shape != null && FormatBytes.getInt(page, start + 44) == shape.partCount()
        && FormatBytes.getLong(page, start + 48) == shape.descriptorHash()
        && (expectedType != TupleBTreePageCodec.TYPE_INTERNAL || rightChild > 0);
  }

  private static int comparePrevious(
      ByteBuffer page, int start, int count,
      ByteBuffer key, int keyOffset, int keyLength) {
    int type = FormatBytes.getInt(page, start + 12);
    int slot = start + TupleBTreePageCodec.HEADER_BYTES
        + (count - 1) * TupleBTreePageCodec.slotBytes(type);
    int offset = FormatBytes.getInt(page, slot);
    int length = FormatBytes.getInt(page, slot + 4);
    return TupleKeyCodec.compare(page, start + offset, length, key, keyOffset, keyLength);
  }
}
