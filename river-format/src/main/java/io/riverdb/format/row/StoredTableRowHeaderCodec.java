package io.riverdb.format.row;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Canonical little-endian framing for one stored table row. */
public final class StoredTableRowHeaderCodec {
  public static final int HEADER_BYTES = 32;
  public static final int VERSION = 1;

  private static final long MAGIC = 0x5249565354524f57L; // RIVSTROW
  private static final int VERSION_OFFSET = 8;
  private static final int FLAGS_OFFSET = 12;
  private static final int ROW_LAYOUT_ID_OFFSET = 16;
  private static final int LOGICAL_ROW_ID_OFFSET = 24;

  private StoredTableRowHeaderCodec() {
  }

  /** Writes a header at {@code start} without changing the target's position or limit. */
  public static StatusCode encode(
      ByteBuffer target,
      int start,
      long rowLayoutId,
      long logicalRowId) {
    if (!validTarget(target, start)
        || rowLayoutId <= 0
        || logicalRowId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + VERSION_OFFSET, VERSION);
    FormatBytes.putInt(target, start + FLAGS_OFFSET, 0);
    FormatBytes.putLong(target, start + ROW_LAYOUT_ID_OFFSET, rowLayoutId);
    FormatBytes.putLong(target, start + LOGICAL_ROW_ID_OFFSET, logicalRowId);
    return StatusCode.OK;
  }

  /** Validates and decodes a header without changing the source's position or limit. */
  public static StatusCode decode(
      ByteBuffer source,
      int start,
      long expectedLogicalRowId,
      StoredTableRowHeader result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null
        || start < 0
        || start > source.limit()
        || expectedLogicalRowId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source.limit() - start < HEADER_BYTES) return StatusCode.CORRUPTION;

    long rowLayoutId = FormatBytes.getLong(source, start + ROW_LAYOUT_ID_OFFSET);
    long logicalRowId = FormatBytes.getLong(source, start + LOGICAL_ROW_ID_OFFSET);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + VERSION_OFFSET) != VERSION
        || FormatBytes.getInt(source, start + FLAGS_OFFSET) != 0
        || rowLayoutId <= 0
        || logicalRowId <= 0
        || logicalRowId != expectedLogicalRowId) {
      return StatusCode.CORRUPTION;
    }
    result.set(rowLayoutId, logicalRowId);
    return StatusCode.OK;
  }

  private static boolean validTarget(ByteBuffer target, int start) {
    return target != null
        && !target.isReadOnly()
        && start >= 0
        && start <= target.limit() - HEADER_BYTES;
  }
}
