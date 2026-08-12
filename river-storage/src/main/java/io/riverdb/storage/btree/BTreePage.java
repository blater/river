package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Fixed-layout single-version B+tree leaf/internal page operations. */
public final class BTreePage {
  public static final int HEADER_BYTES = 32;
  public static final int ENTRY_BYTES = 16;
  public static final int MAX_ENTRIES = 256;
  public static final int TYPE_LEAF = 1;
  public static final int TYPE_INTERNAL = 2;
  public static final int VERSION = 1;

  private static final long MAGIC = 0x5249564552425450L; // RIVERBTP

  private BTreePage() {
  }

  public static StatusCode initializeLeaf(
      ByteBuffer page,
      int rightSiblingPageId,
      long highKey) {
    if (!hasCapacity(page)
        || rightSiblingPageId < 0
        || highKey == Long.MIN_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    initialize(page, TYPE_LEAF);
    putInt(page, 20, rightSiblingPageId);
    putLong(page, 24, highKey);
    return StatusCode.OK;
  }

  public static StatusCode initializeInternal(ByteBuffer page, int firstChildPageId) {
    if (!hasCapacity(page) || firstChildPageId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    initialize(page, TYPE_INTERNAL);
    putInt(page, 20, firstChildPageId);
    putLong(page, 24, Long.MAX_VALUE);
    return StatusCode.OK;
  }

  public static StatusCode validate(ByteBuffer page) {
    if (!hasCapacity(page)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int type = getInt(page, 12);
    int count = getInt(page, 16);
    int pointer = getInt(page, 20);
    long highKey = getLong(page, 24);
    if (getLong(page, 0) != MAGIC
        || getInt(page, 8) != VERSION
        || (type != TYPE_LEAF && type != TYPE_INTERNAL)
        || count < 0
        || count > MAX_ENTRIES
        || (type == TYPE_LEAF && pointer < 0)
        || (type == TYPE_INTERNAL && pointer <= 0)
        || highKey == Long.MIN_VALUE) {
      return StatusCode.CORRUPTION;
    }
    long previous = Long.MIN_VALUE;
    for (int index = 0; index < count; index++) {
      int offset = entryOffset(index);
      long key = getLong(page, offset);
      int value = getInt(page, offset + 8);
      if ((index > 0 && key <= previous)
          || key >= highKey
          || value <= 0
          || getInt(page, offset + 12) != 0) {
        return StatusCode.CORRUPTION;
      }
      previous = key;
    }
    return StatusCode.OK;
  }

  public static int type(ByteBuffer page) {
    return getInt(page, 12);
  }

  public static int entryCount(ByteBuffer page) {
    return getInt(page, 16);
  }

  public static int rightSiblingPageId(ByteBuffer page) {
    return getInt(page, 20);
  }

  public static long highKey(ByteBuffer page) {
    return getLong(page, 24);
  }

  public static int firstChildPageId(ByteBuffer page) {
    return getInt(page, 20);
  }

  public static long keyAt(ByteBuffer page, int index) {
    return getLong(page, entryOffset(index));
  }

  public static int valueAt(ByteBuffer page, int index) {
    return getInt(page, entryOffset(index) + 8);
  }

  public static StatusCode lookupLeaf(
      ByteBuffer page,
      long key,
      BTreeLookupResult result) {
    if (!hasCapacity(page)
        || result == null
        || key == Long.MAX_VALUE
        || getInt(page, 12) != TYPE_LEAF) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int low = 0;
    int high = getInt(page, 16) - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      long candidate = getLong(page, entryOffset(middle));
      if (candidate < key) {
        low = middle + 1;
      } else if (candidate > key) {
        high = middle - 1;
      } else {
        result.setRowId(getInt(page, entryOffset(middle) + 8));
        return StatusCode.OK;
      }
    }
    return StatusCode.CONFLICT;
  }

  public static int childForKey(ByteBuffer page, long key) {
    int child = getInt(page, 20);
    int count = getInt(page, 16);
    for (int index = 0; index < count; index++) {
      int offset = entryOffset(index);
      if (key < getLong(page, offset)) {
        return child;
      }
      child = getInt(page, offset + 8);
    }
    return child;
  }

  public static StatusCode insertLeaf(ByteBuffer page, long key, int rowId) {
    if (!hasCapacity(page)
        || key == Long.MAX_VALUE
        || rowId <= 0
        || getInt(page, 12) != TYPE_LEAF
        || key >= getLong(page, 24)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int count = getInt(page, 16);
    int insertion = insertionPoint(page, key, count);
    if (insertion < count && getLong(page, entryOffset(insertion)) == key) {
      return StatusCode.CONFLICT;
    }
    if (count >= MAX_ENTRIES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    moveEntriesRight(page, insertion, count);
    putEntry(page, insertion, key, rowId);
    putInt(page, 16, count + 1);
    return StatusCode.OK;
  }

  public static StatusCode updateLeaf(ByteBuffer page, long key, int rowId) {
    if (!hasCapacity(page)
        || key == Long.MAX_VALUE
        || rowId <= 0
        || getInt(page, 12) != TYPE_LEAF) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int count = getInt(page, 16);
    int index = insertionPoint(page, key, count);
    if (index >= count || getLong(page, entryOffset(index)) != key) {
      return StatusCode.CONFLICT;
    }
    putInt(page, entryOffset(index) + 8, rowId);
    return StatusCode.OK;
  }

  public static StatusCode splitLeaf(
      ByteBuffer left,
      ByteBuffer right,
      int rightPageId,
      long key,
      int rowId,
      BTreeSplitResult result) {
    if (!hasCapacity(left)
        || !hasCapacity(right)
        || left == right
        || result == null
        || rightPageId <= 0
        || key == Long.MAX_VALUE
        || rowId <= 0
        || getInt(left, 12) != TYPE_LEAF
        || key >= getLong(left, 24)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int count = getInt(left, 16);
    if (count != MAX_ENTRIES) {
      return StatusCode.CONFLICT;
    }
    int insertion = insertionPoint(left, key, count);
    if (insertion < count && getLong(left, entryOffset(insertion)) == key) {
      return StatusCode.CONFLICT;
    }
    int previousRight = getInt(left, 20);
    long previousHigh = getLong(left, 24);
    int splitAt = count / 2;
    StatusCode status = initializeLeaf(right, previousRight, previousHigh);
    if (!status.isOk()) {
      return status;
    }
    int rightCount = count - splitAt;
    for (int index = 0; index < rightCount; index++) {
      copyEntry(left, splitAt + index, right, index);
    }
    putInt(left, 16, splitAt);
    putInt(right, 16, rightCount);
    long separator = getLong(right, entryOffset(0));
    putInt(left, 20, rightPageId);
    putLong(left, 24, separator);
    ByteBuffer target = key < separator ? left : right;
    status = insertLeaf(target, key, rowId);
    if (!status.isOk()) {
      return status;
    }
    separator = getLong(right, entryOffset(0));
    putLong(left, 24, separator);
    clearUnusedEntries(left);
    result.setSeparatorKey(separator);
    return StatusCode.OK;
  }

  public static StatusCode insertInternal(
      ByteBuffer page,
      long separatorKey,
      int rightChildPageId) {
    if (!hasCapacity(page)
        || separatorKey == Long.MAX_VALUE
        || rightChildPageId <= 0
        || getInt(page, 12) != TYPE_INTERNAL
        || separatorKey >= getLong(page, 24)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int count = getInt(page, 16);
    int insertion = insertionPoint(page, separatorKey, count);
    if (insertion < count && getLong(page, entryOffset(insertion)) == separatorKey) {
      return StatusCode.CONFLICT;
    }
    if (count >= MAX_ENTRIES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    moveEntriesRight(page, insertion, count);
    putEntry(page, insertion, separatorKey, rightChildPageId);
    putInt(page, 16, count + 1);
    return StatusCode.OK;
  }

  /** Splits a full internal page after inserting one separator and promotes the middle key. */
  public static StatusCode splitInternal(
      ByteBuffer left,
      ByteBuffer right,
      long separatorKey,
      int rightChildPageId,
      BTreeSplitResult result) {
    if (!hasCapacity(left)
        || !hasCapacity(right)
        || left == right
        || result == null
        || separatorKey == Long.MAX_VALUE
        || rightChildPageId <= 0
        || getInt(left, 12) != TYPE_INTERNAL
        || separatorKey >= getLong(left, 24)
        || left.limit() < entryOffset(MAX_ENTRIES + 1)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int count = getInt(left, 16);
    if (count != MAX_ENTRIES) {
      return StatusCode.CONFLICT;
    }
    int insertion = insertionPoint(left, separatorKey, count);
    if (insertion < count && getLong(left, entryOffset(insertion)) == separatorKey) {
      return StatusCode.CONFLICT;
    }
    moveEntriesRight(left, insertion, count);
    putEntry(left, insertion, separatorKey, rightChildPageId);
    int total = count + 1;
    int promotedIndex = total / 2;
    long promoted = getLong(left, entryOffset(promotedIndex));
    int rightFirstChild = getInt(left, entryOffset(promotedIndex) + 8);
    long previousHigh = getLong(left, 24);
    StatusCode status = initializeInternal(right, rightFirstChild);
    if (!status.isOk()) {
      return status;
    }
    putLong(right, 24, previousHigh);
    int rightCount = total - promotedIndex - 1;
    for (int index = 0; index < rightCount; index++) {
      copyEntry(left, promotedIndex + 1 + index, right, index);
    }
    putInt(right, 16, rightCount);
    putInt(left, 16, promotedIndex);
    putLong(left, 24, promoted);
    clearEntriesFrom(left, promotedIndex);
    clearUnusedEntries(right);
    result.setSeparatorKey(promoted);
    return StatusCode.OK;
  }

  private static void initialize(ByteBuffer page, int type) {
    for (int index = 0; index < page.limit(); index++) {
      page.put(index, (byte) 0);
    }
    putLong(page, 0, MAGIC);
    putInt(page, 8, VERSION);
    putInt(page, 12, type);
  }

  private static boolean hasCapacity(ByteBuffer page) {
    return page != null && page.limit() >= HEADER_BYTES + MAX_ENTRIES * ENTRY_BYTES;
  }

  private static int insertionPoint(ByteBuffer page, long key, int count) {
    int low = 0;
    int high = count;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (getLong(page, entryOffset(middle)) < key) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  private static void moveEntriesRight(ByteBuffer page, int from, int count) {
    for (int index = count; index > from; index--) {
      copyEntry(page, index - 1, page, index);
    }
  }

  private static void copyEntry(
      ByteBuffer source,
      int sourceIndex,
      ByteBuffer target,
      int targetIndex) {
    int sourceOffset = entryOffset(sourceIndex);
    int targetOffset = entryOffset(targetIndex);
    putLong(target, targetOffset, getLong(source, sourceOffset));
    putInt(target, targetOffset + 8, getInt(source, sourceOffset + 8));
    putInt(target, targetOffset + 12, 0);
  }

  private static void putEntry(ByteBuffer page, int index, long key, int value) {
    int offset = entryOffset(index);
    putLong(page, offset, key);
    putInt(page, offset + 8, value);
    putInt(page, offset + 12, 0);
  }

  private static void clearUnusedEntries(ByteBuffer page) {
    int count = getInt(page, 16);
    for (int index = count; index < MAX_ENTRIES; index++) {
      int offset = entryOffset(index);
      putLong(page, offset, 0);
      putInt(page, offset + 8, 0);
      putInt(page, offset + 12, 0);
    }
  }

  private static void clearEntriesFrom(ByteBuffer page, int from) {
    for (int index = from; index <= MAX_ENTRIES; index++) {
      int offset = entryOffset(index);
      putLong(page, offset, 0);
      putInt(page, offset + 8, 0);
      putInt(page, offset + 12, 0);
    }
  }

  private static int entryOffset(int index) {
    return HEADER_BYTES + index * ENTRY_BYTES;
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
