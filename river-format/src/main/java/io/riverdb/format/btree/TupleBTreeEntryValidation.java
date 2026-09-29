package io.riverdb.format.btree;

import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Slot, key, fence, and ordering invariants for one tuple-page entry. */
final class TupleBTreeEntryValidation {
  private TupleBTreeEntryValidation() { }

  static boolean valid(
      ByteBuffer source, int start, int slot, int type,
      int keyOffset, int keyLength, int expectedOffset, int freeStart,
      int highOffset, int highLength, int previousOffset, int previousLength,
      int index, TupleShape shape) {
    return keyLength > 0 && keyLength <= TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
        && keyOffset == expectedOffset && expectedOffset >= freeStart
        && TupleKeyCodec.matchesPhysicalIndexKey(source, start + keyOffset, keyLength, shape)
        && (index == 0 || TupleKeyCodec.compare(
            source, start + previousOffset, previousLength,
            source, start + keyOffset, keyLength) < 0)
        && (highLength == 0 || TupleKeyCodec.compare(
            source, start + keyOffset, keyLength,
            source, start + highOffset, highLength) < 0)
        && (type == TupleBTreePageCodec.TYPE_LEAF
            ? validLeafValue(source, slot, keyOffset, keyLength)
            : FormatBytes.getInt(source, slot + 8) > 0);
  }

  static int inlineValueLength(ByteBuffer source, int slot, int type) {
    return type == TupleBTreePageCodec.TYPE_LEAF && FormatBytes.getInt(source, slot + 16) == 0
        ? FormatBytes.getInt(source, slot + 12) : 0;
  }

  static boolean validLeafValue(
      ByteBuffer source, int slot, int keyOffset, int keyLength) {
    int valueOffset = FormatBytes.getInt(source, slot + 8);
    int valueLength = FormatBytes.getInt(source, slot + 12);
    int overflowPageId = FormatBytes.getInt(source, slot + 16);
    long overflowGeneration = FormatBytes.getLong(source, slot + 20);
    long modificationSequence = FormatBytes.getLong(source, slot + 28);
    if (valueLength < 0 || valueLength > io.riverdb.format.page.PageCodec.MAX_PAYLOAD_BYTES
        || overflowPageId < 0 || modificationSequence < 0
        || FormatBytes.getInt(source, slot + 36) != 0) return false;
    if (overflowPageId != 0) {
      return valueLength > 0 && valueOffset == 0 && overflowGeneration > 0;
    }
    return overflowGeneration == 0
        && valueOffset == (valueLength == 0 ? 0 : keyOffset + keyLength);
  }
}
