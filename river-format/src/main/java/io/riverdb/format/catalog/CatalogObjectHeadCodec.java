package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Durable object head; replacing this record is the sole catalog visibility operation. */
public final class CatalogObjectHeadCodec {
  public static final int VERSION = 2;
  public static final int BYTES = 64;
  public static final int STATE_READY = 1;
  public static final int STATE_TOMBSTONE = 2;
  public static final int STATE_BUILDING = 3;

  private static final long MAGIC = 0x5249564341544f48L; // RIVCATOH
  static final int CHECKSUM_OFFSET = 56;
  static final int COMPLEMENT_OFFSET = 60;

  private CatalogObjectHeadCodec() {
  }

  public static StatusCode encode(
      ByteBuffer target,
      int start,
      int state,
      long objectId,
      long schemaId,
      long catalogGeneration,
      long manifestRecordId,
      CRC32C checksum) {
    if (!writable(target, start, checksum)
        || !valid(state, objectId, schemaId, catalogGeneration, manifestRecordId)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, BYTES);
    FormatBytes.putInt(target, start + 16, state);
    FormatBytes.putInt(target, start + 20, 0);
    FormatBytes.putLong(target, start + 24, objectId);
    FormatBytes.putLong(target, start + 32, schemaId);
    FormatBytes.putLong(target, start + 40, catalogGeneration);
    FormatBytes.putLong(target, start + 48, manifestRecordId);
    int value = FormatBytes.checksum(target, start, CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(target, start + CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + COMPLEMENT_OFFSET, ~value);
    return StatusCode.OK;
  }

  public static StatusCode decode(
      ByteBuffer source, int start, CatalogObjectHead result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || checksum == null || start < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (start > source.limit() - BYTES) return StatusCode.CORRUPTION;
    int stored = FormatBytes.getInt(source, start + CHECKSUM_OFFSET);
    int state = FormatBytes.getInt(source, start + 16);
    long objectId = FormatBytes.getLong(source, start + 24);
    long schemaId = FormatBytes.getLong(source, start + 32);
    long generation = FormatBytes.getLong(source, start + 40);
    long manifestId = FormatBytes.getLong(source, start + 48);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + 12) != BYTES
        || FormatBytes.getInt(source, start + 20) != 0
        || FormatBytes.getInt(source, start + COMPLEMENT_OFFSET) != ~stored
        || FormatBytes.checksum(source, start, CHECKSUM_OFFSET, checksum) != stored
        || !valid(state, objectId, schemaId, generation, manifestId)) {
      return StatusCode.CORRUPTION;
    }
    result.set(state, objectId, schemaId, generation, manifestId);
    return StatusCode.OK;
  }

  private static boolean writable(ByteBuffer target, int start, CRC32C checksum) {
    return target != null && !target.isReadOnly() && checksum != null && start >= 0
        && start <= target.limit() - BYTES;
  }

  private static boolean valid(
      int state, long objectId, long schemaId, long generation, long manifestId) {
    if (!CatalogKeyspace.validObjectHead(objectId) || generation <= 0) return false;
    return state == STATE_READY || state == STATE_BUILDING
        ? schemaId > 0 && manifestId > 0
        : state == STATE_TOMBSTONE && schemaId == 0 && manifestId == 0;
  }
}
