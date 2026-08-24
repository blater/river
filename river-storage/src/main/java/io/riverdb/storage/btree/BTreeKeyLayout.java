package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import java.nio.ByteBuffer;

/** Primitive pair entry and fence layout shared by B-tree page operations. */
final class BTreeKeyLayout {
  private BTreeKeyLayout() {
  }

  static StatusCode validate(ByteBuffer page, long magic) {
    if (page == null
        || page.limit() < BTreePage.HEADER_BYTES
            + BTreePage.MAX_ENTRIES * BTreePage.ENTRY_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int type = getInt(page, 12);
    int count = getInt(page, 16);
    int pointer = getInt(page, 20);
    int highSpace = getInt(page, 32);
    long highKey = getLong(page, 24);
    if (getLong(page, 0) != magic
        || getInt(page, 8) != BTreePage.VERSION
        || type != BTreePage.TYPE_LEAF && type != BTreePage.TYPE_INTERNAL
        || count < 0
        || count > BTreePage.MAX_ENTRIES
        || type == BTreePage.TYPE_LEAF && pointer < 0
        || type == BTreePage.TYPE_INTERNAL && pointer <= 0
        || !OrderedKey.isFiniteSpace(highSpace)
            && !OrderedKey.isInfinity(highSpace, highKey)
        || getInt(page, 36) != 0) {
      return StatusCode.CORRUPTION;
    }
    return validateEntries(page, type, count, highSpace, highKey);
  }

  private static StatusCode validateEntries(
      ByteBuffer page, int type, int count, int highSpace, long highKey) {
    int previousSpace = 0;
    long previousKey = 0;
    boolean hasPrevious = false;
    for (int index = 0; index < count; index++) {
      int offset = entryOffset(index);
      long key = getLong(page, offset);
      int space = getInt(page, offset + 16);
      long value = type == BTreePage.TYPE_LEAF
          ? getLong(page, offset + 8) : Integer.toUnsignedLong(getInt(page, offset + 8));
      if (!OrderedKey.isFiniteSpace(space)
          || hasPrevious
              && !OrderedKey.lessThan(previousSpace, previousKey, space, key)
          || !OrderedKey.isInfinity(highSpace, highKey)
              && !OrderedKey.lessThan(space, key, highSpace, highKey)
          || value <= 0) {
        return StatusCode.CORRUPTION;
      }
      previousSpace = space;
      previousKey = key;
      hasPrevious = true;
    }
    return StatusCode.OK;
  }

  static boolean belowHighKey(ByteBuffer page, int space, long key) {
    int highSpace = getInt(page, 32);
    long highKey = getLong(page, 24);
    return OrderedKey.isInfinity(highSpace, highKey)
        || OrderedKey.lessThan(space, key, highSpace, highKey);
  }

  static boolean entryEquals(
      ByteBuffer page, int index, int space, long key) {
    int offset = entryOffset(index);
    return OrderedKey.equal(
        getInt(page, offset + 16), getLong(page, offset), space, key);
  }

  static int insertionPoint(
      ByteBuffer page, int space, long key, int count) {
    int low = 0;
    int high = count;
    while (low < high) {
      int middle = (low + high) >>> 1;
      int offset = entryOffset(middle);
      if (OrderedKey.lessThan(
          getInt(page, offset + 16), getLong(page, offset), space, key)) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  static void copyEntry(
      ByteBuffer source, int sourceIndex, ByteBuffer target, int targetIndex) {
    int sourceOffset = entryOffset(sourceIndex);
    int targetOffset = entryOffset(targetIndex);
    putLong(target, targetOffset, getLong(source, sourceOffset));
    putLong(target, targetOffset + 8, getLong(source, sourceOffset + 8));
    putInt(target, targetOffset + 16, getInt(source, sourceOffset + 16));
  }

  static void putEntry(
      ByteBuffer page, int index, int space, long key, long value) {
    int offset = entryOffset(index);
    putLong(page, offset, key);
    putLong(page, offset + 8, value);
    putInt(page, offset + 16, space);
  }

  static void putHighKey(ByteBuffer page, int space, long key) {
    putLong(page, 24, key);
    putInt(page, 32, space);
    putInt(page, 36, 0);
  }

  private static int entryOffset(int index) {
    return BTreePage.HEADER_BYTES + index * BTreePage.ENTRY_BYTES;
  }

  private static void putInt(ByteBuffer target, int offset, int value) {
    target.put(offset, (byte) value);
    target.put(offset + 1, (byte) (value >>> 8));
    target.put(offset + 2, (byte) (value >>> 16));
    target.put(offset + 3, (byte) (value >>> 24));
  }

  private static int getInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset))
        | Byte.toUnsignedInt(source.get(offset + 1)) << 8
        | Byte.toUnsignedInt(source.get(offset + 2)) << 16
        | Byte.toUnsignedInt(source.get(offset + 3)) << 24;
  }

  private static void putLong(ByteBuffer target, int offset, long value) {
    putInt(target, offset, (int) value);
    putInt(target, offset + 4, (int) (value >>> 32));
  }

  private static long getLong(ByteBuffer source, int offset) {
    return Integer.toUnsignedLong(getInt(source, offset))
        | Integer.toUnsignedLong(getInt(source, offset + 4)) << 32;
  }
}
