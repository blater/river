package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Canonical v3 primitive-directory B-tree layout with distinct leaf and internal entries. */
public final class BTreePageCodec {
  public static final int VERSION = 3;
  public static final int TYPE_LEAF = 1;
  public static final int TYPE_INTERNAL = 2;
  public static final int HEADER_BYTES = 48;
  public static final int LEAF_ENTRY_BYTES = 24;
  public static final int INTERNAL_ENTRY_BYTES = 16;
  public static final int MAXIMUM_LEAF_ENTRIES =
      (PageCodec.MAX_PAYLOAD_BYTES - HEADER_BYTES) / LEAF_ENTRY_BYTES;
  public static final int MAXIMUM_INTERNAL_ENTRIES =
      (PageCodec.MAX_PAYLOAD_BYTES - HEADER_BYTES) / INTERNAL_ENTRY_BYTES;

  private static final long MAGIC = 0x5249564552425450L; // RIVERBTP

  private BTreePageCodec() {
  }

  /** Initializes and erases one complete primitive-tree payload. */
  public static StatusCode initializePage(
      ByteBuffer target, int start, int type, int pointer, int highSpace, long highKey) {
    if (target == null
        || target.isReadOnly()
        || start < 0
        || target.limit() - start < PageCodec.MAX_PAYLOAD_BYTES
        || !validTypeAndCount(type, 0)
        || type == TYPE_LEAF && pointer < 0
        || type == TYPE_INTERNAL && pointer <= 0
        || type == TYPE_LEAF
            && ((pointer == 0) != OrderedKey.isInfinity(highSpace, highKey))
        || !validFence(highSpace, highKey)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = start; index < start + PageCodec.MAX_PAYLOAD_BYTES; index++) {
      target.put(index, (byte) 0);
    }
    return encodeHeader(target, start, type, 0, pointer, highSpace, highKey);
  }

  public static StatusCode encodeHeader(
      ByteBuffer target,
      int start,
      int type,
      int entryCount,
      int pointer,
      int highSpace,
      long highKey) {
    if (target == null
        || target.isReadOnly()
        || start < 0
        || target.limit() - start < requiredBytes(type, entryCount)
        || !validTypeAndCount(type, entryCount)
        || type == TYPE_LEAF && pointer < 0
        || type == TYPE_INTERNAL && pointer <= 0
        || type == TYPE_LEAF
            && ((pointer == 0) != OrderedKey.isInfinity(highSpace, highKey))
        || !validFence(highSpace, highKey)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, type);
    FormatBytes.putInt(target, start + 16, entryCount);
    FormatBytes.putInt(
        target, start + 20, type == TYPE_LEAF ? LEAF_ENTRY_BYTES : INTERNAL_ENTRY_BYTES);
    FormatBytes.putInt(target, start + 24, pointer);
    FormatBytes.putInt(target, start + 28, 0);
    FormatBytes.putLong(target, start + 32, highKey);
    FormatBytes.putInt(target, start + 40, highSpace);
    FormatBytes.putInt(target, start + 44, 0);
    return StatusCode.OK;
  }

  public static StatusCode decodeHeader(
      ByteBuffer source, int start, BTreePageHeader result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || start < 0 || source.limit() - start < HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int type = FormatBytes.getInt(source, start + 12);
    int count = FormatBytes.getInt(source, start + 16);
    int entryBytes = FormatBytes.getInt(source, start + 20);
    int pointer = FormatBytes.getInt(source, start + 24);
    long highKey = FormatBytes.getLong(source, start + 32);
    int highSpace = FormatBytes.getInt(source, start + 40);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || !validTypeAndCount(type, count)
        || entryBytes != (type == TYPE_LEAF ? LEAF_ENTRY_BYTES : INTERNAL_ENTRY_BYTES)
        || source.limit() - start < requiredBytes(type, count)
        || type == TYPE_LEAF && pointer < 0
        || type == TYPE_INTERNAL && pointer <= 0
        || type == TYPE_LEAF
            && ((pointer == 0) != OrderedKey.isInfinity(highSpace, highKey))
        || FormatBytes.getInt(source, start + 28) != 0
        || !validFence(highSpace, highKey)
        || FormatBytes.getInt(source, start + 44) != 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(type, count, pointer, highSpace, highKey);
    return StatusCode.OK;
  }

  public static StatusCode encodeLeaf(
      ByteBuffer target, int offset, int space, long key, long logicalRowId) {
    if (!validTarget(target, offset, LEAF_ENTRY_BYTES)
        || !OrderedKey.isFiniteSpace(space)
        || logicalRowId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putLong(target, offset, key);
    FormatBytes.putLong(target, offset + 8, logicalRowId);
    FormatBytes.putInt(target, offset + 16, space);
    FormatBytes.putInt(target, offset + 20, 0);
    return StatusCode.OK;
  }

  public static StatusCode decodeLeaf(
      ByteBuffer source,
      int pageStart,
      BTreePageHeader header,
      int index,
      BTreeLeafEntry result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!validEntryContext(source, pageStart, header, index, TYPE_LEAF)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int offset = pageStart + HEADER_BYTES + index * LEAF_ENTRY_BYTES;
    int space = FormatBytes.getInt(source, offset + 16);
    long logicalRowId = FormatBytes.getLong(source, offset + 8);
    if (!OrderedKey.isFiniteSpace(space)
        || logicalRowId <= 0
        || FormatBytes.getInt(source, offset + 20) != 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(space, FormatBytes.getLong(source, offset), logicalRowId);
    return StatusCode.OK;
  }

  public static StatusCode encodeInternal(
      ByteBuffer target, int offset, int space, long key, int rightChildPageId) {
    if (!validTarget(target, offset, INTERNAL_ENTRY_BYTES)
        || !OrderedKey.isFiniteSpace(space)
        || rightChildPageId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putLong(target, offset, key);
    FormatBytes.putInt(target, offset + 8, rightChildPageId);
    FormatBytes.putInt(target, offset + 12, space);
    return StatusCode.OK;
  }

  public static StatusCode decodeInternal(
      ByteBuffer source,
      int pageStart,
      BTreePageHeader header,
      int index,
      BTreeInternalEntry result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!validEntryContext(source, pageStart, header, index, TYPE_INTERNAL)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int offset = pageStart + HEADER_BYTES + index * INTERNAL_ENTRY_BYTES;
    int space = FormatBytes.getInt(source, offset + 12);
    int child = FormatBytes.getInt(source, offset + 8);
    if (!OrderedKey.isFiniteSpace(space) || child <= 0) return StatusCode.CORRUPTION;
    result.set(space, FormatBytes.getLong(source, offset), child);
    return StatusCode.OK;
  }

  /** Validates the complete typed entry array, strict ordering, and high fence. */
  public static StatusCode validatePage(
      ByteBuffer source, int start, BTreePageHeader result) {
    StatusCode status = decodeHeader(source, start, result);
    if (!status.isOk()) return status;
    int type = result.type();
    int entryBytes = type == TYPE_LEAF ? LEAF_ENTRY_BYTES : INTERNAL_ENTRY_BYTES;
    boolean hasPrevious = false;
    int previousSpace = 0;
    long previousKey = 0;
    for (int index = 0; index < result.entryCount(); index++) {
      int offset = start + HEADER_BYTES + index * entryBytes;
      long key = FormatBytes.getLong(source, offset);
      int space;
      boolean validValue;
      if (type == TYPE_LEAF) {
        space = FormatBytes.getInt(source, offset + 16);
        validValue = FormatBytes.getLong(source, offset + 8) > 0
            && FormatBytes.getInt(source, offset + 20) == 0;
      } else {
        space = FormatBytes.getInt(source, offset + 12);
        validValue = FormatBytes.getInt(source, offset + 8) > 0;
      }
      if (!OrderedKey.isFiniteSpace(space)
          || !validValue
          || hasPrevious && !OrderedKey.lessThan(previousSpace, previousKey, space, key)
          || !OrderedKey.isInfinity(result.highSpace(), result.highKey())
              && !OrderedKey.lessThan(space, key, result.highSpace(), result.highKey())) {
        result.reset();
        return StatusCode.CORRUPTION;
      }
      previousSpace = space;
      previousKey = key;
      hasPrevious = true;
    }
    int used = start + HEADER_BYTES + result.entryCount() * entryBytes;
    for (int index = used; index < source.limit(); index++) {
      if (source.get(index) != 0) {
        result.reset();
        return StatusCode.CORRUPTION;
      }
    }
    return StatusCode.OK;
  }

  private static boolean validTarget(ByteBuffer target, int offset, int bytes) {
    return target != null
        && !target.isReadOnly()
        && offset >= 0
        && target.limit() - offset >= bytes;
  }

  private static boolean validEntryContext(
      ByteBuffer source,
      int pageStart,
      BTreePageHeader header,
      int index,
      int expectedType) {
    if (source == null
        || header == null
        || header.type() != expectedType
        || index < 0
        || index >= header.entryCount()
        || pageStart < 0) {
      return false;
    }
    int entryBytes = expectedType == TYPE_LEAF ? LEAF_ENTRY_BYTES : INTERNAL_ENTRY_BYTES;
    return source.limit() - pageStart >= HEADER_BYTES + header.entryCount() * entryBytes;
  }

  private static boolean validTypeAndCount(int type, int count) {
    return count >= 0
        && (type == TYPE_LEAF && count <= MAXIMUM_LEAF_ENTRIES
            || type == TYPE_INTERNAL && count <= MAXIMUM_INTERNAL_ENTRIES);
  }

  private static int requiredBytes(int type, int count) {
    if (!validTypeAndCount(type, count)) return Integer.MAX_VALUE;
    int entryBytes = type == TYPE_LEAF ? LEAF_ENTRY_BYTES : INTERNAL_ENTRY_BYTES;
    return HEADER_BYTES + count * entryBytes;
  }

  private static boolean validFence(int space, long key) {
    return OrderedKey.isFiniteSpace(space) || OrderedKey.isInfinity(space, key);
  }
}
