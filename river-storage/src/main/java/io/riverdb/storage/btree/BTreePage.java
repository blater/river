package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import java.nio.ByteBuffer;

/** Fixed-layout single-version B+tree leaf/internal page operations. */
public final class BTreePage {
  public static final int HEADER_BYTES = 40;
  public static final int ENTRY_BYTES = 16;
  public static final int MAX_ENTRIES = 256;
  public static final int TYPE_LEAF = 1;
  public static final int TYPE_INTERNAL = 2;
  public static final int VERSION = 2;

  private static final long MAGIC = 0x5249564552425450L; // RIVERBTP

  private BTreePage() {
  }

  public static StatusCode initializeLeaf(
      ByteBuffer page,
      int rightSiblingPageId) {
    if (!hasCapacity(page) || rightSiblingPageId < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    initialize(page, TYPE_LEAF);
    putInt(page, 20, rightSiblingPageId);
    BTreeKeyLayout.putHighKey(page, OrderedKey.INFINITY_SPACE, 0);
    return StatusCode.OK;
  }

  public static StatusCode initializeInternal(ByteBuffer page, int firstChildPageId) {
    if (!hasCapacity(page) || firstChildPageId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    initialize(page, TYPE_INTERNAL);
    putInt(page, 20, firstChildPageId);
    BTreeKeyLayout.putHighKey(page, OrderedKey.INFINITY_SPACE, 0);
    return StatusCode.OK;
  }

  public static StatusCode validate(ByteBuffer page) {
    return BTreeKeyLayout.validate(page, MAGIC);
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

  public static int highSpace(ByteBuffer page) {
    return getInt(page, 32);
  }

  public static int firstChildPageId(ByteBuffer page) {
    return getInt(page, 20);
  }

  public static long keyAt(ByteBuffer page, int index) {
    return getLong(page, entryOffset(index));
  }

  public static int spaceAt(ByteBuffer page, int index) {
    return getInt(page, entryOffset(index) + 12);
  }

  public static int valueAt(ByteBuffer page, int index) {
    return getInt(page, entryOffset(index) + 8);
  }

  public static StatusCode lookupLeaf(
      ByteBuffer page,
      int space,
      long key,
      BTreeLookupResult result) {
    if (!hasCapacity(page)
        || result == null
        || !OrderedKey.isFiniteSpace(space)
        || getInt(page, 12) != TYPE_LEAF) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int low = 0;
    int high = getInt(page, 16) - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      int offset = entryOffset(middle);
      long candidate = getLong(page, offset);
      int candidateSpace = getInt(page, offset + 12);
      int comparison = OrderedKey.compare(candidateSpace, candidate, space, key);
      if (comparison < 0) {
        low = middle + 1;
      } else if (comparison > 0) {
        high = middle - 1;
      } else {
        result.setRowId(getInt(page, entryOffset(middle) + 8));
        return StatusCode.OK;
      }
    }
    return StatusCode.CONFLICT;
  }

  public static int childForKey(ByteBuffer page, int space, long key) {
    int child = getInt(page, 20);
    int count = getInt(page, 16);
    for (int index = 0; index < count; index++) {
      int offset = entryOffset(index);
      if (OrderedKey.lessThan(
          space, key, getInt(page, offset + 12), getLong(page, offset))) {
        return child;
      }
      child = getInt(page, offset + 8);
    }
    return child;
  }

  public static StatusCode insertLeaf(
      ByteBuffer page, int space, long key, int rowId) {
    if (!hasCapacity(page)
        || !OrderedKey.isFiniteSpace(space)
        || rowId <= 0
        || getInt(page, 12) != TYPE_LEAF
        || !BTreeKeyLayout.belowHighKey(page, space, key)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int count = getInt(page, 16);
    int insertion = BTreeKeyLayout.insertionPoint(page, space, key, count);
    if (insertion < count && BTreeKeyLayout.entryEquals(page, insertion, space, key)) {
      return StatusCode.CONFLICT;
    }
    if (count >= MAX_ENTRIES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    moveEntriesRight(page, insertion, count);
    BTreeKeyLayout.putEntry(page, insertion, space, key, rowId);
    putInt(page, 16, count + 1);
    return StatusCode.OK;
  }

  public static StatusCode updateLeaf(
      ByteBuffer page, int space, long key, int rowId) {
    if (!hasCapacity(page)
        || !OrderedKey.isFiniteSpace(space)
        || rowId <= 0
        || getInt(page, 12) != TYPE_LEAF) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int count = getInt(page, 16);
    int index = BTreeKeyLayout.insertionPoint(page, space, key, count);
    if (index >= count || !BTreeKeyLayout.entryEquals(page, index, space, key)) {
      return StatusCode.CONFLICT;
    }
    putInt(page, entryOffset(index) + 8, rowId);
    return StatusCode.OK;
  }

  public static StatusCode splitLeaf(
      ByteBuffer left,
      ByteBuffer right,
      int rightPageId,
      int space,
      long key,
      int rowId,
      BTreeSplitResult result) {
    if (!hasCapacity(left)
        || !hasCapacity(right)
        || left == right
        || result == null
        || rightPageId <= 0
        || !OrderedKey.isFiniteSpace(space)
        || rowId <= 0
        || getInt(left, 12) != TYPE_LEAF
        || !BTreeKeyLayout.belowHighKey(left, space, key)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int count = getInt(left, 16);
    if (count != MAX_ENTRIES) {
      return StatusCode.CONFLICT;
    }
    int insertion = BTreeKeyLayout.insertionPoint(left, space, key, count);
    if (insertion < count && BTreeKeyLayout.entryEquals(left, insertion, space, key)) {
      return StatusCode.CONFLICT;
    }
    int previousRight = getInt(left, 20);
    long previousHigh = getLong(left, 24);
    int previousHighSpace = getInt(left, 32);
    int splitAt = count / 2;
    StatusCode status = initializeLeaf(right, previousRight);
    if (!status.isOk()) {
      return status;
    }
    int rightCount = count - splitAt;
    for (int index = 0; index < rightCount; index++) {
      BTreeKeyLayout.copyEntry(left, splitAt + index, right, index);
    }
    putInt(left, 16, splitAt);
    putInt(right, 16, rightCount);
    long separator = getLong(right, entryOffset(0));
    int separatorSpace = getInt(right, entryOffset(0) + 12);
    putInt(left, 20, rightPageId);
    BTreeKeyLayout.putHighKey(left, separatorSpace, separator);
    BTreeKeyLayout.putHighKey(right, previousHighSpace, previousHigh);
    ByteBuffer target = OrderedKey.lessThan(space, key, separatorSpace, separator)
        ? left : right;
    status = insertLeaf(target, space, key, rowId);
    if (!status.isOk()) {
      return status;
    }
    separator = getLong(right, entryOffset(0));
    separatorSpace = getInt(right, entryOffset(0) + 12);
    BTreeKeyLayout.putHighKey(left, separatorSpace, separator);
    clearUnusedEntries(left);
    result.setSeparator(separatorSpace, separator);
    return StatusCode.OK;
  }

  public static StatusCode insertInternal(
      ByteBuffer page,
      int separatorSpace,
      long separatorKey,
      int rightChildPageId) {
    if (!hasCapacity(page)
        || !OrderedKey.isFiniteSpace(separatorSpace)
        || rightChildPageId <= 0
        || getInt(page, 12) != TYPE_INTERNAL
        || !BTreeKeyLayout.belowHighKey(page, separatorSpace, separatorKey)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int count = getInt(page, 16);
    int insertion = BTreeKeyLayout.insertionPoint(
        page, separatorSpace, separatorKey, count);
    if (insertion < count
        && BTreeKeyLayout.entryEquals(
            page, insertion, separatorSpace, separatorKey)) {
      return StatusCode.CONFLICT;
    }
    if (count >= MAX_ENTRIES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    moveEntriesRight(page, insertion, count);
    BTreeKeyLayout.putEntry(
        page, insertion, separatorSpace, separatorKey, rightChildPageId);
    putInt(page, 16, count + 1);
    return StatusCode.OK;
  }

  /** Splits a full internal page after inserting one separator and promotes the middle key. */
  public static StatusCode splitInternal(
      ByteBuffer left,
      ByteBuffer right,
      int separatorSpace,
      long separatorKey,
      int rightChildPageId,
      BTreeSplitResult result) {
    if (!hasCapacity(left)
        || !hasCapacity(right)
        || left == right
        || result == null
        || !OrderedKey.isFiniteSpace(separatorSpace)
        || rightChildPageId <= 0
        || getInt(left, 12) != TYPE_INTERNAL
        || !BTreeKeyLayout.belowHighKey(left, separatorSpace, separatorKey)
        || left.limit() < entryOffset(MAX_ENTRIES + 1)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int count = getInt(left, 16);
    if (count != MAX_ENTRIES) {
      return StatusCode.CONFLICT;
    }
    int insertion = BTreeKeyLayout.insertionPoint(
        left, separatorSpace, separatorKey, count);
    if (insertion < count
        && BTreeKeyLayout.entryEquals(
            left, insertion, separatorSpace, separatorKey)) {
      return StatusCode.CONFLICT;
    }
    moveEntriesRight(left, insertion, count);
    BTreeKeyLayout.putEntry(
        left, insertion, separatorSpace, separatorKey, rightChildPageId);
    int total = count + 1;
    int promotedIndex = total / 2;
    long promoted = getLong(left, entryOffset(promotedIndex));
    int promotedSpace = getInt(left, entryOffset(promotedIndex) + 12);
    int rightFirstChild = getInt(left, entryOffset(promotedIndex) + 8);
    long previousHigh = getLong(left, 24);
    int previousHighSpace = getInt(left, 32);
    StatusCode status = initializeInternal(right, rightFirstChild);
    if (!status.isOk()) {
      return status;
    }
    BTreeKeyLayout.putHighKey(right, previousHighSpace, previousHigh);
    int rightCount = total - promotedIndex - 1;
    for (int index = 0; index < rightCount; index++) {
      BTreeKeyLayout.copyEntry(left, promotedIndex + 1 + index, right, index);
    }
    putInt(right, 16, rightCount);
    putInt(left, 16, promotedIndex);
    BTreeKeyLayout.putHighKey(left, promotedSpace, promoted);
    clearEntriesFrom(left, promotedIndex);
    clearUnusedEntries(right);
    result.setSeparator(promotedSpace, promoted);
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

  private static void moveEntriesRight(ByteBuffer page, int from, int count) {
    for (int index = count; index > from; index--) {
      BTreeKeyLayout.copyEntry(page, index - 1, page, index);
    }
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
