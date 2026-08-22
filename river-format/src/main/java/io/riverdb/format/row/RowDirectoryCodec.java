package io.riverdb.format.row;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Canonical records separating stable logical rows from changing physical MVCC versions. */
public final class RowDirectoryCodec {
  public static final int VERSION = 1;
  public static final int LOGICAL_RECORD_BYTES = 48;
  public static final int VERSION_RECORD_BYTES = 88;
  public static final int MAXIMUM_TABLE_ID = 0x7fff;
  // floor((PageCodec.MAX_PAYLOAD_BYTES - HeapPage.HEADER_BYTES) /
  //     (HeapPage.SLOT_BYTES + one minimum row byte)) for the current heap layout.
  public static final int MAXIMUM_HEAP_SLOT_ID = 1_802;

  private static final long LOGICAL_MAGIC = 0x5249564c4f47524fL; // RIVLOGRO
  private static final long VERSION_MAGIC = 0x524956564552534eL; // RIVVERSN
  private static final int KEYLESS_FLAG = 1;
  private static final int DELETED_FLAG = 1;

  private RowDirectoryCodec() {
  }

  public static StatusCode encodeLogical(
      ByteBuffer target,
      int tableId,
      long logicalRowId,
      long headVersionId,
      boolean keyless,
      CRC32C checksum) {
    if (target == null
        || target.isReadOnly()
        || target.remaining() < LOGICAL_RECORD_BYTES
        || tableId <= 0
        || tableId > MAXIMUM_TABLE_ID
        || logicalRowId <= 0
        || headVersionId <= 0
        || checksum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = target.position();
    FormatBytes.putLong(target, start, LOGICAL_MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, tableId);
    FormatBytes.putLong(target, start + 16, logicalRowId);
    FormatBytes.putLong(target, start + 24, headVersionId);
    FormatBytes.putInt(target, start + 32, keyless ? KEYLESS_FLAG : 0);
    FormatBytes.putInt(target, start + 36, 0);
    seal(target, start, 40, 44, checksum);
    target.limit(start + LOGICAL_RECORD_BYTES);
    return StatusCode.OK;
  }

  public static StatusCode decodeLogical(
      ByteBuffer source, long expectedLogicalRowId, LogicalRowRecord result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || expectedLogicalRowId <= 0 || checksum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source.remaining() != LOGICAL_RECORD_BYTES) return StatusCode.CORRUPTION;
    int start = source.position();
    int flags = FormatBytes.getInt(source, start + 32);
    if (FormatBytes.getLong(source, start) != LOGICAL_MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + 12) <= 0
        || FormatBytes.getInt(source, start + 12) > MAXIMUM_TABLE_ID
        || FormatBytes.getLong(source, start + 16) <= 0
        || FormatBytes.getLong(source, start + 16) != expectedLogicalRowId
        || FormatBytes.getLong(source, start + 24) <= 0
        || (flags & ~KEYLESS_FLAG) != 0
        || FormatBytes.getInt(source, start + 36) != 0
        || !sealed(source, start, 40, 44, checksum)) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        FormatBytes.getInt(source, start + 12),
        FormatBytes.getLong(source, start + 16),
        FormatBytes.getLong(source, start + 24),
        (flags & KEYLESS_FLAG) != 0);
    return StatusCode.OK;
  }

  public static StatusCode encodeVersion(
      ByteBuffer target,
      int tableId,
      long versionId,
      long logicalRowId,
      long previousVersionId,
      long commitSequence,
      long pageNumber,
      long pageGeneration,
      int heapSlotId,
      int slotGeneration,
      boolean deleted,
      CRC32C checksum) {
    if (target == null
        || target.isReadOnly()
        || target.remaining() < VERSION_RECORD_BYTES
        || tableId <= 0
        || tableId > MAXIMUM_TABLE_ID
        || versionId <= 0
        || logicalRowId <= 0
        || previousVersionId < 0
        || previousVersionId >= versionId
        || commitSequence <= 0
        || pageNumber <= 0
        || pageGeneration <= 0
        || heapSlotId <= 0
        || heapSlotId > MAXIMUM_HEAP_SLOT_ID
        || slotGeneration <= 0
        || checksum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = target.position();
    FormatBytes.putLong(target, start, VERSION_MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, tableId);
    FormatBytes.putLong(target, start + 16, versionId);
    FormatBytes.putLong(target, start + 24, logicalRowId);
    FormatBytes.putLong(target, start + 32, previousVersionId);
    FormatBytes.putLong(target, start + 40, commitSequence);
    FormatBytes.putLong(target, start + 48, pageNumber);
    FormatBytes.putLong(target, start + 56, pageGeneration);
    FormatBytes.putInt(target, start + 64, heapSlotId);
    FormatBytes.putInt(target, start + 68, slotGeneration);
    FormatBytes.putInt(target, start + 72, deleted ? DELETED_FLAG : 0);
    FormatBytes.putInt(target, start + 76, 0);
    seal(target, start, 80, 84, checksum);
    target.limit(start + VERSION_RECORD_BYTES);
    return StatusCode.OK;
  }

  public static StatusCode decodeVersion(
      ByteBuffer source, long expectedVersionId, VersionRecord result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || expectedVersionId <= 0 || checksum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source.remaining() != VERSION_RECORD_BYTES) return StatusCode.CORRUPTION;
    int start = source.position();
    long versionId = FormatBytes.getLong(source, start + 16);
    long previousId = FormatBytes.getLong(source, start + 32);
    int flags = FormatBytes.getInt(source, start + 72);
    if (FormatBytes.getLong(source, start) != VERSION_MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + 12) <= 0
        || FormatBytes.getInt(source, start + 12) > MAXIMUM_TABLE_ID
        || versionId <= 0
        || versionId != expectedVersionId
        || FormatBytes.getLong(source, start + 24) <= 0
        || previousId < 0
        || previousId >= versionId
        || FormatBytes.getLong(source, start + 40) <= 0
        || FormatBytes.getLong(source, start + 48) <= 0
        || FormatBytes.getLong(source, start + 56) <= 0
        || FormatBytes.getInt(source, start + 64) <= 0
        || FormatBytes.getInt(source, start + 64) > MAXIMUM_HEAP_SLOT_ID
        || FormatBytes.getInt(source, start + 68) <= 0
        || (flags & ~DELETED_FLAG) != 0
        || FormatBytes.getInt(source, start + 76) != 0
        || !sealed(source, start, 80, 84, checksum)) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        FormatBytes.getInt(source, start + 12),
        versionId,
        FormatBytes.getLong(source, start + 24),
        previousId,
        FormatBytes.getLong(source, start + 40),
        FormatBytes.getLong(source, start + 48),
        FormatBytes.getLong(source, start + 56),
        FormatBytes.getInt(source, start + 64),
        FormatBytes.getInt(source, start + 68),
        (flags & DELETED_FLAG) != 0);
    return StatusCode.OK;
  }

  private static void seal(
      ByteBuffer target,
      int start,
      int checksumOffset,
      int complementOffset,
      CRC32C checksum) {
    int value = FormatBytes.checksum(target, start, checksumOffset, checksum);
    FormatBytes.putInt(target, start + checksumOffset, value);
    FormatBytes.putInt(target, start + complementOffset, ~value);
  }

  private static boolean sealed(
      ByteBuffer source,
      int start,
      int checksumOffset,
      int complementOffset,
      CRC32C checksum) {
    int value = FormatBytes.getInt(source, start + checksumOffset);
    return FormatBytes.getInt(source, start + complementOffset) == ~value
        && FormatBytes.checksum(source, start, checksumOffset, checksum) == value;
  }
}
