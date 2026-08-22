package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Compact slotted B-tree payload with inline canonical tuple keys. */
public final class TupleBTreePageCodec {
  public static final int VERSION = 1;
  public static final int TYPE_LEAF = 1;
  public static final int TYPE_INTERNAL = 2;
  public static final int HEADER_BYTES = 80;
  public static final int SLOT_BYTES = 16;
  public static final int MAXIMUM_SLOTS =
      (PageCodec.MAX_PAYLOAD_BYTES - HEADER_BYTES) / SLOT_BYTES;

  private static final long MAGIC = 0x5249565455425450L; // RIVTUBTP

  private TupleBTreePageCodec() {
  }

  /** Initializes one empty payload, owning and erasing the complete payload range. */
  public static StatusCode initialize(
      ByteBuffer target,
      int start,
      int type,
      int pointer,
      int keyArity,
      int firstDescriptor,
      int secondDescriptor,
      int thirdDescriptor,
      int fourthDescriptor,
      long keySchemaId,
      ByteBuffer highKey,
      int highKeyOffset,
      int highKeyLength) {
    if (!validPayload(target, start, true)
        || type != TYPE_LEAF && type != TYPE_INTERNAL
        || type == TYPE_LEAF && ((pointer == 0) != (highKeyLength == 0))
        || type == TYPE_INTERNAL && pointer <= 0
        || !TupleKeyCodec.validShape(
            keyArity,
            firstDescriptor,
            secondDescriptor,
            thirdDescriptor,
            fourthDescriptor)
        || keySchemaId <= 0
        || highKeyLength < 0
        || highKeyLength > TupleKeyCodec.MAXIMUM_KEY_BYTES
        || highKeyLength > 0
            && (highKey == null
                || highKeyOffset < 0
                || highKey.limit() - highKeyOffset < highKeyLength
                || !TupleKeyCodec.matchesShape(
                    highKey,
                    highKeyOffset,
                    highKeyLength,
                    keyArity,
                    firstDescriptor,
                    secondDescriptor,
                    thirdDescriptor,
                    fourthDescriptor))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int end = start + PageCodec.MAX_PAYLOAD_BYTES;
    for (int index = start; index < end; index++) target.put(index, (byte) 0);
    int highOffset = 0;
    int freeEnd = PageCodec.MAX_PAYLOAD_BYTES;
    if (highKeyLength > 0) {
      freeEnd -= highKeyLength;
      highOffset = freeEnd;
      copy(highKey, highKeyOffset, target, start + highOffset, highKeyLength);
    }
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, type);
    FormatBytes.putInt(target, start + 16, 0);
    FormatBytes.putInt(target, start + 20, SLOT_BYTES);
    FormatBytes.putInt(target, start + 24, pointer);
    FormatBytes.putInt(target, start + 28, HEADER_BYTES);
    FormatBytes.putInt(target, start + 32, freeEnd);
    FormatBytes.putInt(target, start + 36, highOffset);
    FormatBytes.putInt(target, start + 40, highKeyLength);
    FormatBytes.putInt(target, start + 44, keyArity);
    FormatBytes.putInt(target, start + 48, firstDescriptor);
    FormatBytes.putInt(target, start + 52, secondDescriptor);
    FormatBytes.putInt(target, start + 56, thirdDescriptor);
    FormatBytes.putInt(target, start + 60, fourthDescriptor);
    FormatBytes.putLong(target, start + 64, keySchemaId);
    FormatBytes.putLong(target, start + 72, 0);
    return StatusCode.OK;
  }

  public static StatusCode appendLeaf(
      ByteBuffer page,
      int start,
      ByteBuffer key,
      int keyOffset,
      int keyLength,
      long logicalRowId) {
    return append(page, start, TYPE_LEAF, key, keyOffset, keyLength, logicalRowId, 0);
  }

  public static StatusCode appendInternal(
      ByteBuffer page,
      int start,
      ByteBuffer key,
      int keyOffset,
      int keyLength,
      int rightChildPageId) {
    return append(page, start, TYPE_INTERNAL, key, keyOffset, keyLength, 0, rightChildPageId);
  }

  public static StatusCode readLeaf(
      ByteBuffer source,
      int start,
      TupleBTreePageHeader header,
      int index,
      TupleBTreeLeafEntry result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!validRead(source, start, header, index, TYPE_LEAF)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int slot = start + HEADER_BYTES + index * SLOT_BYTES;
    result.set(
        FormatBytes.getInt(source, slot),
        FormatBytes.getInt(source, slot + 4),
        FormatBytes.getLong(source, slot + 8));
    return StatusCode.OK;
  }

  public static StatusCode readInternal(
      ByteBuffer source,
      int start,
      TupleBTreePageHeader header,
      int index,
      TupleBTreeInternalEntry result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!validRead(source, start, header, index, TYPE_INTERNAL)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int slot = start + HEADER_BYTES + index * SLOT_BYTES;
    result.set(
        FormatBytes.getInt(source, slot),
        FormatBytes.getInt(source, slot + 4),
        FormatBytes.getInt(source, slot + 8));
    return StatusCode.OK;
  }

  /** Allocation-free complete-page validation, including compact packing and zero slack. */
  public static StatusCode validate(
      ByteBuffer source,
      int start,
      long expectedSchemaId,
      int expectedArity,
      int expectedFirstDescriptor,
      int expectedSecondDescriptor,
      int expectedThirdDescriptor,
      int expectedFourthDescriptor,
      TupleBTreePageHeader result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!validPayload(source, start, false)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int type = FormatBytes.getInt(source, start + 12);
    int count = FormatBytes.getInt(source, start + 16);
    int pointer = FormatBytes.getInt(source, start + 24);
    int freeStart = FormatBytes.getInt(source, start + 28);
    int freeEnd = FormatBytes.getInt(source, start + 32);
    int highOffset = FormatBytes.getInt(source, start + 36);
    int highLength = FormatBytes.getInt(source, start + 40);
    int arity = FormatBytes.getInt(source, start + 44);
    int firstDescriptor = FormatBytes.getInt(source, start + 48);
    int secondDescriptor = FormatBytes.getInt(source, start + 52);
    int thirdDescriptor = FormatBytes.getInt(source, start + 56);
    int fourthDescriptor = FormatBytes.getInt(source, start + 60);
    long schemaId = FormatBytes.getLong(source, start + 64);
    boolean infinite = highLength == 0;
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || type != TYPE_LEAF && type != TYPE_INTERNAL
        || count < 0
        || count > MAXIMUM_SLOTS
        || FormatBytes.getInt(source, start + 20) != SLOT_BYTES
        || type == TYPE_LEAF && ((pointer == 0) != infinite)
        || type == TYPE_INTERNAL && pointer <= 0
        || freeStart != HEADER_BYTES + count * SLOT_BYTES
        || freeStart > freeEnd
        || freeEnd > PageCodec.MAX_PAYLOAD_BYTES
        || highLength < 0
        || highLength > TupleKeyCodec.MAXIMUM_KEY_BYTES
        || infinite && highOffset != 0
        || FormatBytes.getLong(source, start + 72) != 0
        || schemaId <= 0
        || schemaId != expectedSchemaId
        || arity != expectedArity
        || firstDescriptor != expectedFirstDescriptor
        || secondDescriptor != expectedSecondDescriptor
        || thirdDescriptor != expectedThirdDescriptor
        || fourthDescriptor != expectedFourthDescriptor
        || !TupleKeyCodec.validShape(
            arity,
            firstDescriptor,
            secondDescriptor,
            thirdDescriptor,
            fourthDescriptor)) {
      return StatusCode.CORRUPTION;
    }
    int cursor = PageCodec.MAX_PAYLOAD_BYTES;
    if (!infinite) {
      cursor -= highLength;
      if (highOffset != cursor
          || !TupleKeyCodec.matchesShape(
              source,
              start + highOffset,
              highLength,
              arity,
              firstDescriptor,
              secondDescriptor,
              thirdDescriptor,
              fourthDescriptor)) {
        return StatusCode.CORRUPTION;
      }
    }
    int previousOffset = 0;
    int previousLength = 0;
    for (int index = 0; index < count; index++) {
      int slot = start + HEADER_BYTES + index * SLOT_BYTES;
      int keyOffset = FormatBytes.getInt(source, slot);
      int keyLength = FormatBytes.getInt(source, slot + 4);
      cursor -= keyLength;
      if (keyLength <= 0
          || keyLength > TupleKeyCodec.MAXIMUM_KEY_BYTES
          || keyOffset != cursor
          || cursor < freeStart
          || !TupleKeyCodec.matchesShape(
              source,
              start + keyOffset,
              keyLength,
              arity,
              firstDescriptor,
              secondDescriptor,
              thirdDescriptor,
              fourthDescriptor)
          || index > 0 && TupleKeyCodec.compare(
              source, start + previousOffset, previousLength,
              source, start + keyOffset, keyLength) >= 0
          || !infinite && TupleKeyCodec.compare(
              source, start + keyOffset, keyLength,
              source, start + highOffset, highLength) >= 0
          || !validSlotValue(source, slot, type, start + keyOffset, keyLength)) {
        return StatusCode.CORRUPTION;
      }
      previousOffset = keyOffset;
      previousLength = keyLength;
    }
    if (freeEnd != cursor) return StatusCode.CORRUPTION;
    for (int index = start + freeStart; index < start + freeEnd; index++) {
      if (source.get(index) != 0) return StatusCode.CORRUPTION;
    }
    result.set(
        type,
        count,
        pointer,
        arity,
        firstDescriptor,
        secondDescriptor,
        thirdDescriptor,
        fourthDescriptor,
        schemaId,
        highOffset,
        highLength);
    return StatusCode.OK;
  }

  private static StatusCode append(
      ByteBuffer page,
      int start,
      int expectedType,
      ByteBuffer key,
      int keyOffset,
      int keyLength,
      long logicalRowId,
      int rightChildPageId) {
    if (!validPayload(page, start, true)
        || key == null
        || keyOffset < 0
        || keyLength <= 0
        || key.limit() - keyOffset < keyLength
        || !TupleKeyCodec.validate(key, keyOffset, keyLength)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int type = FormatBytes.getInt(page, start + 12);
    int count = FormatBytes.getInt(page, start + 16);
    int freeStart = FormatBytes.getInt(page, start + 28);
    int freeEnd = FormatBytes.getInt(page, start + 32);
    int highOffset = FormatBytes.getInt(page, start + 36);
    int highLength = FormatBytes.getInt(page, start + 40);
    int arity = FormatBytes.getInt(page, start + 44);
    int firstDescriptor = FormatBytes.getInt(page, start + 48);
    int secondDescriptor = FormatBytes.getInt(page, start + 52);
    int thirdDescriptor = FormatBytes.getInt(page, start + 56);
    int fourthDescriptor = FormatBytes.getInt(page, start + 60);
    if (FormatBytes.getLong(page, start) != MAGIC
        || FormatBytes.getInt(page, start + 8) != VERSION
        || type != expectedType
        || count < 0
        || count >= MAXIMUM_SLOTS
        || freeStart != HEADER_BYTES + count * SLOT_BYTES
        || !TupleKeyCodec.matchesShape(
            key,
            keyOffset,
            keyLength,
            arity,
            firstDescriptor,
            secondDescriptor,
            thirdDescriptor,
            fourthDescriptor)
        || expectedType == TYPE_LEAF
            && (logicalRowId <= 0
                || TupleKeyCodec.logicalRowId(key, keyOffset, keyLength) != logicalRowId)
        || expectedType == TYPE_INTERNAL && rightChildPageId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (count > 0) {
      int previousSlot = start + HEADER_BYTES + (count - 1) * SLOT_BYTES;
      int previousOffset = FormatBytes.getInt(page, previousSlot);
      int previousLength = FormatBytes.getInt(page, previousSlot + 4);
      if (TupleKeyCodec.compare(
          page, start + previousOffset, previousLength, key, keyOffset, keyLength) >= 0) {
        return StatusCode.CONFLICT;
      }
    }
    if (highLength > 0 && TupleKeyCodec.compare(
        key, keyOffset, keyLength, page, start + highOffset, highLength) >= 0) {
      return StatusCode.CONFLICT;
    }
    int newFreeStart = freeStart + SLOT_BYTES;
    int newFreeEnd = freeEnd - keyLength;
    if (newFreeStart > newFreeEnd) return StatusCode.RESOURCE_EXHAUSTED;
    copy(key, keyOffset, page, start + newFreeEnd, keyLength);
    int slot = start + freeStart;
    FormatBytes.putInt(page, slot, newFreeEnd);
    FormatBytes.putInt(page, slot + 4, keyLength);
    if (expectedType == TYPE_LEAF) {
      FormatBytes.putLong(page, slot + 8, logicalRowId);
    } else {
      FormatBytes.putInt(page, slot + 8, rightChildPageId);
      FormatBytes.putInt(page, slot + 12, 0);
    }
    FormatBytes.putInt(page, start + 16, count + 1);
    FormatBytes.putInt(page, start + 28, newFreeStart);
    FormatBytes.putInt(page, start + 32, newFreeEnd);
    return StatusCode.OK;
  }

  private static boolean validSlotValue(
      ByteBuffer source, int slot, int type, int keyOffset, int keyLength) {
    if (type == TYPE_LEAF) {
      long logicalId = FormatBytes.getLong(source, slot + 8);
      return logicalId > 0
          && TupleKeyCodec.logicalRowId(source, keyOffset, keyLength) == logicalId;
    }
    return FormatBytes.getInt(source, slot + 8) > 0
        && FormatBytes.getInt(source, slot + 12) == 0;
  }

  private static boolean validPayload(ByteBuffer page, int start, boolean writable) {
    return page != null
        && (!writable || !page.isReadOnly())
        && start >= 0
        && page.limit() - start >= PageCodec.MAX_PAYLOAD_BYTES;
  }

  private static boolean validRead(
      ByteBuffer source,
      int start,
      TupleBTreePageHeader header,
      int index,
      int expectedType) {
    return validPayload(source, start, false)
        && header != null
        && header.type() == expectedType
        && index >= 0
        && index < header.entryCount();
  }

  private static void copy(
      ByteBuffer source, int sourceOffset, ByteBuffer target, int targetOffset, int bytes) {
    for (int index = 0; index < bytes; index++) {
      target.put(targetOffset + index, source.get(sourceOffset + index));
    }
  }
}
