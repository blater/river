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
            ? FormatBytes.getInt(source, slot + 8) == 0
            : FormatBytes.getInt(source, slot + 8) > 0);
  }
}
