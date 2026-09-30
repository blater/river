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
    return insertLeaf(page, start, schemaId, shape, key, keyOffset, keyLength,
        null, 0, 0, 0, 0, 0, insertion, capability);
  }

  static StatusCode insertLeaf(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      ByteBuffer value, int valueOffset, int valueLength,
      int overflowPageId, long overflowGeneration, long modificationSequence,
      int insertion, TupleBTreePageMutationCapability capability) {
    if (capability == null || !capability.matches(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF)
        || key == page || !TupleKeyCodec.matchesPhysicalIndexKey(
            key, keyOffset, keyLength, shape)
        || !TupleBTreePageAppend.validLeafValueInput(
            value, valueOffset, valueLength, overflowPageId,
            overflowGeneration, modificationSequence)
        || value == page) {
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
    int resultingFreeStart = freeStart + TupleBTreePageCodec.LEAF_SLOT_BYTES;
    int inlineLength = overflowPageId == 0 ? valueLength : 0;
    int recordLength = keyLength + inlineLength;
    int resultingFreeEnd = freeEnd - recordLength;
    if (resultingFreeStart > resultingFreeEnd) {
      capability.reset();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int highKeyOffset = capability.highKeyOffset();
    int highKeyLength = capability.highKeyLength();
    int keyEnd = insertion == 0
        ? highKeyLength == 0 ? PageCodec.MAX_PAYLOAD_BYTES : highKeyOffset
        : keyOffsetAt(page, start, insertion - 1);

    move(page, start + freeEnd, start + freeEnd - recordLength, keyEnd - freeEnd);
    moveReverse(
        page, start + TupleBTreePageCodec.HEADER_BYTES
            + insertion * TupleBTreePageCodec.LEAF_SLOT_BYTES,
        start + TupleBTreePageCodec.HEADER_BYTES
            + (insertion + 1) * TupleBTreePageCodec.LEAF_SLOT_BYTES,
        (count - insertion) * TupleBTreePageCodec.LEAF_SLOT_BYTES);
    for (int index = insertion + 1; index <= count; index++) {
      int slot = slot(start, index);
      FormatBytes.putInt(page, slot, FormatBytes.getInt(page, slot) - recordLength);
      shiftInlineValueOffset(page, slot, -recordLength);
    }
    int insertedSlot = slot(start, insertion);
    int insertedOffset = keyEnd - recordLength;
    TupleBTreePageBytes.copy(key, keyOffset, page, start + insertedOffset, keyLength);
    if (inlineLength > 0) {
      TupleBTreePageBytes.copy(
          value, valueOffset, page, start + insertedOffset + keyLength, inlineLength);
    }
    FormatBytes.putInt(page, insertedSlot, insertedOffset);
    FormatBytes.putInt(page, insertedSlot + 4, keyLength);
    FormatBytes.putInt(
        page, insertedSlot + 8, inlineLength == 0 ? 0 : insertedOffset + keyLength);
    FormatBytes.putInt(page, insertedSlot + 12, valueLength);
    FormatBytes.putInt(page, insertedSlot + 16, overflowPageId);
    FormatBytes.putLong(page, insertedSlot + 20, overflowGeneration);
    FormatBytes.putLong(page, insertedSlot + 28, modificationSequence);
    FormatBytes.putInt(page, insertedSlot + 36, 0);
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
    deletedLength += TupleBTreeEntryValidation.inlineValueLength(
        page, deletedSlot, TupleBTreePageCodec.TYPE_LEAF);

    moveReverse(
        page, start + freeEnd, start + freeEnd + deletedLength,
        deletedOffset - freeEnd);
    move(
        page, deletedSlot + TupleBTreePageCodec.LEAF_SLOT_BYTES, deletedSlot,
        (count - deletion - 1) * TupleBTreePageCodec.LEAF_SLOT_BYTES);
    for (int index = deletion; index < count - 1; index++) {
      int retainedSlot = slot(start, index);
      FormatBytes.putInt(
          page, retainedSlot, FormatBytes.getInt(page, retainedSlot) + deletedLength);
      shiftInlineValueOffset(page, retainedSlot, deletedLength);
    }
    int resultingFreeStart = freeStart - TupleBTreePageCodec.LEAF_SLOT_BYTES;
    int resultingFreeEnd = freeEnd + deletedLength;
    zero(page, start + resultingFreeStart, start + freeStart);
    zero(page, start + freeEnd, start + resultingFreeEnd);
    FormatBytes.putInt(page, start + 16, count - 1);
    FormatBytes.putInt(page, start + 28, resultingFreeStart);
    FormatBytes.putInt(page, start + 32, resultingFreeEnd);
    return capability.sealValidation();
  }

  static StatusCode replaceLeafValue(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      ByteBuffer value, int valueOffset, int valueLength,
      int overflowPageId, long overflowGeneration, long modificationSequence,
      int index, TupleBTreePageMutationCapability capability) {
    if (capability == null || !capability.matches(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF)
        || key == page || value == page
        || !TupleKeyCodec.matchesPhysicalIndexKey(key, keyOffset, keyLength, shape)
        || !TupleBTreePageAppend.validLeafValueInput(
            value, valueOffset, valueLength, overflowPageId,
            overflowGeneration, modificationSequence)) {
      if (capability != null) capability.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (index < 0 || index >= capability.entryCount()
        || FormatBytes.getInt(page, slot(start, index) + 4) != keyLength
        || compareAt(page, start, index, key, keyOffset, keyLength) != 0) {
      capability.reset();
      return StatusCode.CONFLICT;
    }
    int target = slot(start, index);
    int oldKeyOffset = FormatBytes.getInt(page, target);
    int oldKeyLength = FormatBytes.getInt(page, target + 4);
    int oldInlineLength = TupleBTreeEntryValidation.inlineValueLength(
        page, target, TupleBTreePageCodec.TYPE_LEAF);
    int newInlineLength = overflowPageId == 0 ? valueLength : 0;
    int change = newInlineLength - oldInlineLength;
    int freeEnd = capability.freeEnd();
    int newFreeEnd = freeEnd - change;
    int freeStart = FormatBytes.getInt(page, start + 28);
    if (newFreeEnd < freeStart) {
      capability.reset();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (change > 0) move(page, start + freeEnd, start + newFreeEnd,
        oldKeyOffset - freeEnd);
    else if (change < 0) moveReverse(page, start + freeEnd, start + newFreeEnd,
        oldKeyOffset - freeEnd);
    int newKeyOffset = oldKeyOffset - change;
    TupleBTreePageBytes.copy(key, keyOffset, page, start + newKeyOffset, oldKeyLength);
    if (newInlineLength > 0) TupleBTreePageBytes.copy(
        value, valueOffset, page, start + newKeyOffset + oldKeyLength, newInlineLength);
    if (change < 0) zero(page, start + freeEnd, start + newFreeEnd);
    for (int retained = index + 1; retained < capability.entryCount(); retained++) {
      int retainedSlot = slot(start, retained);
      FormatBytes.putInt(page, retainedSlot, FormatBytes.getInt(page, retainedSlot) - change);
      shiftInlineValueOffset(page, retainedSlot, -change);
    }
    FormatBytes.putInt(page, target, newKeyOffset);
    FormatBytes.putInt(
        page, target + 8, newInlineLength == 0 ? 0 : newKeyOffset + oldKeyLength);
    FormatBytes.putInt(page, target + 12, valueLength);
    FormatBytes.putInt(page, target + 16, overflowPageId);
    FormatBytes.putLong(page, target + 20, overflowGeneration);
    FormatBytes.putLong(page, target + 28, modificationSequence);
    FormatBytes.putInt(page, start + 32, newFreeEnd);
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
        + index * TupleBTreePageCodec.LEAF_SLOT_BYTES;
  }

  private static void shiftInlineValueOffset(ByteBuffer page, int slot, int delta) {
    int valueOffset = FormatBytes.getInt(page, slot + 8);
    if (valueOffset != 0) FormatBytes.putInt(page, slot + 8, valueOffset + delta);
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
