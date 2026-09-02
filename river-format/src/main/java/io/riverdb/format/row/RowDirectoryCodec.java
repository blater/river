package io.riverdb.format.row;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Canonical records separating stable logical rows from changing physical MVCC versions. */
public final class RowDirectoryCodec {
  public static final int VERSION = 2;
  public static final int LOGICAL_RECORD_BYTES = 48;
  public static final int VERSION_RECORD_BYTES = 88;
  // floor((PageCodec.MAX_PAYLOAD_BYTES - HeapPage.HEADER_BYTES) /
  //     (HeapPage.SLOT_BYTES + one minimum row byte)) for the current heap layout.
  public static final int MAXIMUM_HEAP_SLOT_ID = 1_802;

  private static final long LOGICAL_MAGIC = 0x5249564c4f47524fL; // RIVLOGRO
  private static final long VERSION_MAGIC = 0x524956564552534eL; // RIVVERSN
  private static final int FLAGS_OFFSET = 12;
  private static final int OBJECT_ID_OFFSET = 16;
  private static final int KEYLESS_FLAG = 1;
  private static final int DELETED_FLAG = 1;

  private RowDirectoryCodec() {
  }

  public static StatusCode encodeLogical(
      ByteBuffer target,
      long objectId,
      long logicalRowId,
      long headHeapVersionId,
      boolean keyless,
      CRC32C checksum) {
    if (target == null
        || target.isReadOnly()
        || target.remaining() < LOGICAL_RECORD_BYTES
        || !CatalogKeyspace.validObjectHead(objectId)
        || logicalRowId <= 0
        || headHeapVersionId <= 0
        || checksum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = target.position();
    FormatBytes.putLong(target, start, LOGICAL_MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + FLAGS_OFFSET, keyless ? KEYLESS_FLAG : 0);
    FormatBytes.putLong(target, start + OBJECT_ID_OFFSET, objectId);
    FormatBytes.putLong(target, start + 24, logicalRowId);
    FormatBytes.putLong(target, start + 32, headHeapVersionId);
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
    int flags = FormatBytes.getInt(source, start + FLAGS_OFFSET);
    if (FormatBytes.getLong(source, start) != LOGICAL_MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || !CatalogKeyspace.validObjectHead(
            FormatBytes.getLong(source, start + OBJECT_ID_OFFSET))
        || FormatBytes.getLong(source, start + 24) <= 0
        || FormatBytes.getLong(source, start + 24) != expectedLogicalRowId
        || FormatBytes.getLong(source, start + 32) <= 0
        || (flags & ~KEYLESS_FLAG) != 0
        || !sealed(source, start, 40, 44, checksum)) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        FormatBytes.getLong(source, start + OBJECT_ID_OFFSET),
        FormatBytes.getLong(source, start + 24),
        FormatBytes.getLong(source, start + 32),
        (flags & KEYLESS_FLAG) != 0);
    return StatusCode.OK;
  }

  public static StatusCode encodeVersion(
      ByteBuffer target,
      long objectId,
      long heapVersionId,
      long logicalRowId,
      long previousHeapVersionId,
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
        || !CatalogKeyspace.validObjectHead(objectId)
        || heapVersionId <= 0
        || logicalRowId <= 0
        || previousHeapVersionId < 0
        || previousHeapVersionId >= heapVersionId
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
    FormatBytes.putInt(target, start + FLAGS_OFFSET, deleted ? DELETED_FLAG : 0);
    FormatBytes.putLong(target, start + OBJECT_ID_OFFSET, objectId);
    FormatBytes.putLong(target, start + 24, heapVersionId);
    FormatBytes.putLong(target, start + 32, logicalRowId);
    FormatBytes.putLong(target, start + 40, previousHeapVersionId);
    FormatBytes.putLong(target, start + 48, commitSequence);
    FormatBytes.putLong(target, start + 56, pageNumber);
    FormatBytes.putLong(target, start + 64, pageGeneration);
    FormatBytes.putInt(target, start + 72, heapSlotId);
    FormatBytes.putInt(target, start + 76, slotGeneration);
    seal(target, start, 80, 84, checksum);
    target.limit(start + VERSION_RECORD_BYTES);
    return StatusCode.OK;
  }

  public static StatusCode decodeVersion(
      ByteBuffer source, long expectedHeapVersionId, VersionRecord result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || expectedHeapVersionId <= 0 || checksum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source.remaining() != VERSION_RECORD_BYTES) return StatusCode.CORRUPTION;
    int start = source.position();
    long heapVersionId = FormatBytes.getLong(source, start + 24);
    long previousHeapVersionId = FormatBytes.getLong(source, start + 40);
    int flags = FormatBytes.getInt(source, start + FLAGS_OFFSET);
    if (FormatBytes.getLong(source, start) != VERSION_MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || !CatalogKeyspace.validObjectHead(
            FormatBytes.getLong(source, start + OBJECT_ID_OFFSET))
        || heapVersionId <= 0
        || heapVersionId != expectedHeapVersionId
        || FormatBytes.getLong(source, start + 32) <= 0
        || previousHeapVersionId < 0
        || previousHeapVersionId >= heapVersionId
        || FormatBytes.getLong(source, start + 48) <= 0
        || FormatBytes.getLong(source, start + 56) <= 0
        || FormatBytes.getLong(source, start + 64) <= 0
        || FormatBytes.getInt(source, start + 72) <= 0
        || FormatBytes.getInt(source, start + 72) > MAXIMUM_HEAP_SLOT_ID
        || FormatBytes.getInt(source, start + 76) <= 0
        || (flags & ~DELETED_FLAG) != 0
        || !sealed(source, start, 80, 84, checksum)) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        FormatBytes.getLong(source, start + OBJECT_ID_OFFSET),
        heapVersionId,
        FormatBytes.getLong(source, start + 32),
        previousHeapVersionId,
        FormatBytes.getLong(source, start + 48),
        FormatBytes.getLong(source, start + 56),
        FormatBytes.getLong(source, start + 64),
        FormatBytes.getInt(source, start + 72),
        FormatBytes.getInt(source, start + 76),
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
