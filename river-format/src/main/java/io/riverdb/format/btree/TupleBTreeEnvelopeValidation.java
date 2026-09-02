package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Descriptor-independent structural validation used during lower store recovery. */
final class TupleBTreeEnvelopeValidation {
  private TupleBTreeEnvelopeValidation() { }

  static StatusCode validate(
      ByteBuffer source, int start, TupleBTreePageHeader result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!TupleBTreePageBytes.validPayload(source, start, false)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int type = FormatBytes.getInt(source, start + 12);
    int count = FormatBytes.getInt(source, start + 16);
    int pointer = FormatBytes.getInt(source, start + 24);
    int leftSibling = FormatBytes.getInt(source, start + 64);
    int freeStart = FormatBytes.getInt(source, start + 28);
    int freeEnd = FormatBytes.getInt(source, start + 32);
    int highOffset = FormatBytes.getInt(source, start + 36);
    int highLength = FormatBytes.getInt(source, start + 40);
    int arity = FormatBytes.getInt(source, start + 44);
    long hash = FormatBytes.getLong(source, start + 48);
    long schema = FormatBytes.getLong(source, start + 56);
    if (!validHeader(source, start, type, count, pointer, leftSibling, freeStart, freeEnd,
        highOffset, highLength, arity, hash, schema)) return StatusCode.CORRUPTION;
    int cursor = PageCodec.MAX_PAYLOAD_BYTES;
    if (highLength > 0) {
      cursor -= highLength;
      if (highOffset != cursor || !validKey(source, start + highOffset, highLength, arity)) {
        return StatusCode.CORRUPTION;
      }
    }
    int previousOffset = 0;
    int previousLength = 0;
    for (int index = 0; index < count; index++) {
      int slot = start + TupleBTreePageCodec.HEADER_BYTES
          + index * TupleBTreePageCodec.SLOT_BYTES;
      int keyOffset = FormatBytes.getInt(source, slot);
      int keyLength = FormatBytes.getInt(source, slot + 4);
      cursor -= keyLength;
      if (!validEntry(source, start, slot, type, keyOffset, keyLength,
          cursor, freeStart, highOffset, highLength,
          previousOffset, previousLength, index, arity)) return StatusCode.CORRUPTION;
      previousOffset = keyOffset;
      previousLength = keyLength;
    }
    if (freeEnd != cursor
        || !TupleBTreePageBytes.zeroRange(source, start + freeStart, start + freeEnd)) {
      return StatusCode.CORRUPTION;
    }
    result.set(type, count, pointer, leftSibling, arity, hash, schema, highOffset, highLength);
    return StatusCode.OK;
  }

  private static boolean validHeader(
      ByteBuffer source, int start, int type, int count, int pointer, int leftSibling,
      int freeStart, int freeEnd, int highOffset, int highLength,
      int arity, long hash, long schema) {
    return FormatBytes.getLong(source, start) == TupleBTreePageCodec.MAGIC
        && FormatBytes.getInt(source, start + 8) == TupleBTreePageCodec.VERSION
        && (type == TupleBTreePageCodec.TYPE_LEAF || type == TupleBTreePageCodec.TYPE_INTERNAL)
        && count >= 0 && count <= TupleBTreePageCodec.MAXIMUM_SLOTS
        && FormatBytes.getInt(source, start + 20) == TupleBTreePageCodec.SLOT_BYTES
        && TupleBTreePageBytes.validLinks(type, leftSibling, pointer, highLength)
        && freeStart == TupleBTreePageCodec.HEADER_BYTES
            + count * TupleBTreePageCodec.SLOT_BYTES
        && freeStart <= freeEnd && freeEnd <= PageCodec.MAX_PAYLOAD_BYTES
        && highLength >= 0 && highLength <= TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
        && (highLength != 0 || highOffset == 0)
        && arity > 0 && arity <= SqlShapeLimits.MAX_KEY_PARTS
        && hash != 0 && schema > 0 && FormatBytes.getInt(source, start + 68) == 0;
  }

  private static boolean validEntry(
      ByteBuffer source, int start, int slot, int type,
      int keyOffset, int keyLength, int expectedOffset, int freeStart,
      int highOffset, int highLength, int previousOffset, int previousLength,
      int index, int arity) {
    return keyLength > 0 && keyLength <= TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
        && keyOffset == expectedOffset && expectedOffset >= freeStart
        && validKey(source, start + keyOffset, keyLength, arity)
        && (index == 0 || TupleKeyCodec.compare(
            source, start + previousOffset, previousLength,
            source, start + keyOffset, keyLength) < 0)
        && (highLength == 0 || TupleKeyCodec.compare(
            source, start + keyOffset, keyLength,
            source, start + highOffset, highLength) < 0)
        && (type == TupleBTreePageCodec.TYPE_LEAF
            ? FormatBytes.getInt(source, slot + 8) == 0
            : FormatBytes.getInt(source, slot + 8) > 0);
  }

  private static boolean validKey(ByteBuffer source, int offset, int length, int arity) {
    return TupleKeyStructureValidation.validate(source, offset, length)
        && TupleKeyCodec.isPhysical(source, offset, length)
        && TupleKeyCodec.arity(source, offset, length) == arity;
  }
}
