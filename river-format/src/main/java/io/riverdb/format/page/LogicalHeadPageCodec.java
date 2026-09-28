package io.riverdb.format.page;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;

/** Sparse page layout for table roots and table-local logical-row heads. */
public final class LogicalHeadPageCodec {
  public static final int VERSION = 1;
  public static final int HEADER_BYTES = 40;
  public static final int TABLE_BRANCH = 1;
  public static final int TABLE_LEAF = 2;
  public static final int ROW_BRANCH = 3;
  public static final int ROW_LEAF = 4;
  public static final int TABLE_ROOT_LEVEL = 2;
  public static final int MAXIMUM_ROW_ROOT_LEVEL = 5;
  public static final int BRANCH_ENTRIES =
      (PageCodec.MAX_PAYLOAD_BYTES - HEADER_BYTES) / Integer.BYTES;
  public static final int TABLE_LEAF_ENTRIES =
      (PageCodec.MAX_PAYLOAD_BYTES - HEADER_BYTES) / Long.BYTES;
  public static final int ROW_LEAF_ENTRIES =
      (PageCodec.MAX_PAYLOAD_BYTES - HEADER_BYTES) / Long.BYTES;

  private static final long MAGIC = 0x5249564845414431L; // RIVHEAD1
  private static final int VERSION_OFFSET = 8;
  private static final int TYPE_OFFSET = 12;
  private static final int OWNER_OFFSET = 16;
  private static final int BASE_OFFSET = 24;
  private static final int LEVEL_OFFSET = 32;

  private LogicalHeadPageCodec() {}

  public static StatusCode initialize(
      ByteBuffer target, int type, long ownerObjectId, long base, int level) {
    if (!validBuffer(target) || !validIdentity(type, ownerObjectId, base, level)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int offset = 0; offset < target.limit(); offset++) target.put(offset, (byte) 0);
    FormatBytes.putLong(target, 0, MAGIC);
    FormatBytes.putInt(target, VERSION_OFFSET, VERSION);
    FormatBytes.putInt(target, TYPE_OFFSET, type);
    FormatBytes.putLong(target, OWNER_OFFSET, ownerObjectId);
    FormatBytes.putLong(target, BASE_OFFSET, base);
    FormatBytes.putInt(target, LEVEL_OFFSET, level);
    return StatusCode.OK;
  }

  public static StatusCode validate(ByteBuffer source) {
    if (!validBuffer(source)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int type = type(source);
    long owner = ownerObjectId(source);
    long base = base(source);
    int level = level(source);
    return FormatBytes.getLong(source, 0) == MAGIC
        && FormatBytes.getInt(source, VERSION_OFFSET) == VERSION
        && validIdentity(type, owner, base, level)
        && FormatBytes.getInt(source, 36) == 0
            ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  public static int type(ByteBuffer source) {
    return FormatBytes.getInt(source, TYPE_OFFSET);
  }

  public static long ownerObjectId(ByteBuffer source) {
    return FormatBytes.getLong(source, OWNER_OFFSET);
  }

  public static long base(ByteBuffer source) {
    return FormatBytes.getLong(source, BASE_OFFSET);
  }

  public static int level(ByteBuffer source) {
    return FormatBytes.getInt(source, LEVEL_OFFSET);
  }

  public static int branchChild(ByteBuffer source, int slot) {
    return FormatBytes.getInt(source, HEADER_BYTES + slot * Integer.BYTES);
  }

  public static void branchChild(ByteBuffer target, int slot, int pageId) {
    FormatBytes.putInt(target, HEADER_BYTES + slot * Integer.BYTES, pageId);
  }

  public static int tableRootPageId(ByteBuffer source, int slot) {
    return FormatBytes.getInt(source, tableEntryOffset(slot));
  }

  public static int tableRootLevel(ByteBuffer source, int slot) {
    return FormatBytes.getInt(source, tableEntryOffset(slot) + Integer.BYTES);
  }

  public static void tableRoot(
      ByteBuffer target, int slot, int rootPageId, int rootLevel) {
    int offset = tableEntryOffset(slot);
    FormatBytes.putInt(target, offset, rootPageId);
    FormatBytes.putInt(target, offset + Integer.BYTES, rootLevel);
  }

  public static long rowHead(ByteBuffer source, int slot) {
    return FormatBytes.getLong(source, HEADER_BYTES + slot * Long.BYTES);
  }

  public static void rowHead(ByteBuffer target, int slot, long versionRowId) {
    FormatBytes.putLong(target, HEADER_BYTES + slot * Long.BYTES, versionRowId);
  }

  private static int tableEntryOffset(int slot) {
    return HEADER_BYTES + slot * Long.BYTES;
  }

  private static boolean validBuffer(ByteBuffer bytes) {
    return bytes != null && bytes.limit() >= PageCodec.MAX_PAYLOAD_BYTES;
  }

  private static boolean validIdentity(int type, long owner, long base, int level) {
    if (base < 0 || level < 0) return false;
    return switch (type) {
      case TABLE_BRANCH -> owner == 0 && level > 0 && level <= TABLE_ROOT_LEVEL;
      case TABLE_LEAF -> owner == 0 && level == 0;
      case ROW_BRANCH -> CatalogKeyspace.validObjectHead(owner)
          && level > 0 && level <= MAXIMUM_ROW_ROOT_LEVEL;
      case ROW_LEAF -> CatalogKeyspace.validObjectHead(owner) && level == 0;
      default -> false;
    };
  }
}
