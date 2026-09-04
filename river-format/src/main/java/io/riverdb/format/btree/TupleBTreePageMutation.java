package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** In-place mutation of a completely validated canonical tuple leaf. */
final class TupleBTreePageMutation {
  private TupleBTreePageMutation() { }

  static StatusCode insertLeaf(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength, int insertion,
      TupleBTreePageMutationCapability capability) {
    if (capability == null || !capability.matches(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF)
        || key == page || !TupleKeyCodec.matchesPhysicalIndexKey(
            key, keyOffset, keyLength, shape)) {
      if (capability != null) capability.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int count = capability.entryCount();
    if (insertion < 0 || insertion > count
        || !orderedAt(page, start, key, keyOffset, keyLength, insertion, capability)) {
      capability.reset();
      return StatusCode.CONFLICT;
    }
    int freeStart = FormatBytes.getInt(page, start + 28);
    int freeEnd = capability.freeEnd();
    int resultingFreeStart = freeStart + TupleBTreePageCodec.SLOT_BYTES;
    int resultingFreeEnd = freeEnd - keyLength;
    if (resultingFreeStart > resultingFreeEnd) {
      capability.reset();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int highKeyOffset = capability.highKeyOffset();
    int highKeyLength = capability.highKeyLength();
    int keyEnd = insertion == 0
        ? highKeyLength == 0 ? PageCodec.MAX_PAYLOAD_BYTES : highKeyOffset
        : keyOffsetAt(page, start, insertion - 1);

    move(page, start + freeEnd, start + freeEnd - keyLength, keyEnd - freeEnd);
    moveReverse(
        page, start + TupleBTreePageCodec.HEADER_BYTES
            + insertion * TupleBTreePageCodec.SLOT_BYTES,
        start + TupleBTreePageCodec.HEADER_BYTES
            + (insertion + 1) * TupleBTreePageCodec.SLOT_BYTES,
        (count - insertion) * TupleBTreePageCodec.SLOT_BYTES);
    for (int index = insertion + 1; index <= count; index++) {
      int slot = slot(start, index);
      FormatBytes.putInt(page, slot, FormatBytes.getInt(page, slot) - keyLength);
    }
    int insertedSlot = slot(start, insertion);
    int insertedOffset = keyEnd - keyLength;
    TupleBTreePageBytes.copy(key, keyOffset, page, start + insertedOffset, keyLength);
    FormatBytes.putInt(page, insertedSlot, insertedOffset);
    FormatBytes.putInt(page, insertedSlot + 4, keyLength);
    FormatBytes.putInt(page, insertedSlot + 8, 0);
    FormatBytes.putInt(page, start + 16, count + 1);
    FormatBytes.putInt(page, start + 28, resultingFreeStart);
    FormatBytes.putInt(page, start + 32, resultingFreeEnd);
    return capability.sealValidation();
  }

  static StatusCode deleteLeaf(
      ByteBuffer page, int start, long schemaId, TupleShape shape, int deletion,
      TupleBTreePageMutationCapability capability) {
    if (capability == null || !capability.matches(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF)
        || deletion < 0 || deletion >= capability.entryCount()) {
      if (capability != null) capability.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int count = capability.entryCount();
    int freeStart = FormatBytes.getInt(page, start + 28);
    int freeEnd = capability.freeEnd();
    int deletedSlot = slot(start, deletion);
    int deletedOffset = FormatBytes.getInt(page, deletedSlot);
    int deletedLength = FormatBytes.getInt(page, deletedSlot + 4);

    moveReverse(
        page, start + freeEnd, start + freeEnd + deletedLength,
        deletedOffset - freeEnd);
    move(
        page, deletedSlot + TupleBTreePageCodec.SLOT_BYTES, deletedSlot,
        (count - deletion - 1) * TupleBTreePageCodec.SLOT_BYTES);
    for (int index = deletion; index < count - 1; index++) {
      int retainedSlot = slot(start, index);
      FormatBytes.putInt(
          page, retainedSlot, FormatBytes.getInt(page, retainedSlot) + deletedLength);
    }
    int resultingFreeStart = freeStart - TupleBTreePageCodec.SLOT_BYTES;
    int resultingFreeEnd = freeEnd + deletedLength;
    zero(page, start + resultingFreeStart, start + freeStart);
    zero(page, start + freeEnd, start + resultingFreeEnd);
    FormatBytes.putInt(page, start + 16, count - 1);
    FormatBytes.putInt(page, start + 28, resultingFreeStart);
    FormatBytes.putInt(page, start + 32, resultingFreeEnd);
    return capability.sealValidation();
  }

  private static boolean orderedAt(
      ByteBuffer page, int start, ByteBuffer key, int keyOffset, int keyLength,
      int insertion, TupleBTreePageMutationCapability capability) {
    if (insertion > 0 && compareAt(
        page, start, insertion - 1, key, keyOffset, keyLength) >= 0) return false;
    if (insertion < capability.entryCount() && compareAt(
        page, start, insertion, key, keyOffset, keyLength) <= 0) return false;
    return capability.highKeyLength() == 0 || TupleKeyCodec.compare(
        key, keyOffset, keyLength,
        page, start + capability.highKeyOffset(), capability.highKeyLength()) < 0;
  }

  private static int compareAt(
      ByteBuffer page, int start, int index,
      ByteBuffer key, int keyOffset, int keyLength) {
    int slot = slot(start, index);
    int offset = FormatBytes.getInt(page, slot);
    int length = FormatBytes.getInt(page, slot + 4);
    return TupleKeyCodec.compare(page, start + offset, length, key, keyOffset, keyLength);
  }

  private static int keyOffsetAt(ByteBuffer page, int start, int index) {
    return FormatBytes.getInt(page, slot(start, index));
  }

  private static int slot(int start, int index) {
    return start + TupleBTreePageCodec.HEADER_BYTES
        + index * TupleBTreePageCodec.SLOT_BYTES;
  }

  private static void move(
      ByteBuffer page, int source, int target, int length) {
    for (int index = 0; index < length; index++) {
      page.put(target + index, page.get(source + index));
    }
  }

  private static void moveReverse(
      ByteBuffer page, int source, int target, int length) {
    for (int index = length - 1; index >= 0; index--) {
      page.put(target + index, page.get(source + index));
    }
  }

  private static void zero(ByteBuffer page, int from, int to) {
    for (int index = from; index < to; index++) page.put(index, (byte) 0);
  }
}
