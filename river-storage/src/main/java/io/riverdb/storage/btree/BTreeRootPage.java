package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Durable root/allocation metadata for the first B+tree. */
public final class BTreeRootPage {
  public static final int BYTES = 32;
  public static final int VERSION = 1;

  private static final long MAGIC = 0x5249564552425452L; // RIVERBTR

  private BTreeRootPage() {
  }

  public static StatusCode initialize(ByteBuffer page, int rootPageId, int nextPageId) {
    if (page == null || page.limit() < BYTES || rootPageId <= 0 || nextPageId <= rootPageId) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    clear(page);
    putLong(page, 0, MAGIC);
    putInt(page, 8, VERSION);
    putInt(page, 12, rootPageId);
    putInt(page, 16, nextPageId);
    return StatusCode.OK;
  }

  public static StatusCode validate(ByteBuffer page) {
    if (page == null || page.limit() < BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int rootPageId = getInt(page, 12);
    int nextPageId = getInt(page, 16);
    if (getLong(page, 0) != MAGIC
        || getInt(page, 8) != VERSION
        || rootPageId <= 0
        || nextPageId <= rootPageId
        || getInt(page, 20) != 0
        || getLong(page, 24) != 0) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  public static int rootPageId(ByteBuffer page) {
    return getInt(page, 12);
  }

  public static int nextPageId(ByteBuffer page) {
    return getInt(page, 16);
  }

  public static void publishRoot(ByteBuffer page, int rootPageId) {
    putInt(page, 12, rootPageId);
  }

  public static int allocatePage(ByteBuffer page) {
    int allocated = getInt(page, 16);
    putInt(page, 16, allocated + 1);
    return allocated;
  }

  private static void clear(ByteBuffer page) {
    for (int index = 0; index < page.limit(); index++) {
      page.put(index, (byte) 0);
    }
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
