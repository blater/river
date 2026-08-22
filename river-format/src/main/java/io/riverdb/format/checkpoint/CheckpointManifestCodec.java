package io.riverdb.format.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Fixed v3 checkpoint authority containing only roots, watermarks, and bounded extents. */
public final class CheckpointManifestCodec {
  public static final int VERSION = 3;
  public static final int MAXIMUM_EXTENTS = 64;
  public static final int HEADER_BYTES = 160;
  public static final int EXTENT_BYTES = 16;
  public static final int EXTENTS_OFFSET = HEADER_BYTES;
  public static final int CHECKSUM_OFFSET = EXTENTS_OFFSET + MAXIMUM_EXTENTS * EXTENT_BYTES;
  public static final int BYTES = CHECKSUM_OFFSET + 8;

  private static final long MAGIC = 0x524956434b504d33L; // RIVCKPM3

  private CheckpointManifestCodec() {
  }

  public static StatusCode begin(
      ByteBuffer target,
      int start,
      long databaseHigh,
      long databaseLow,
      long walGeneration,
      long checkpointId,
      long commitSequence,
      long maximumTransactionId,
      long maximumLogicalRowId,
      long maximumVersionId,
      int nextPageId,
      int rootDirectoryPageId,
      int logicalDirectoryPageId,
      int versionDirectoryPageId,
      int freePageRootId,
      int extentCount,
      long rootDirectoryGeneration,
      long logicalDirectoryGeneration,
      long versionDirectoryGeneration,
      long freePageGeneration,
      long storageGeneration) {
    if (target == null
        || target.isReadOnly()
        || start < 0
        || target.limit() - start < BYTES
        || !validHeader(
            databaseHigh,
            databaseLow,
            walGeneration,
            checkpointId,
            commitSequence,
            maximumTransactionId,
            maximumLogicalRowId,
            maximumVersionId,
            nextPageId,
            rootDirectoryPageId,
            logicalDirectoryPageId,
            versionDirectoryPageId,
            freePageRootId,
            extentCount,
            rootDirectoryGeneration,
            logicalDirectoryGeneration,
            versionDirectoryGeneration,
            freePageGeneration,
            storageGeneration)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = start; index < start + BYTES; index++) target.put(index, (byte) 0);
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, BYTES);
    FormatBytes.putLong(target, start + 16, databaseHigh);
    FormatBytes.putLong(target, start + 24, databaseLow);
    FormatBytes.putLong(target, start + 32, walGeneration);
    FormatBytes.putLong(target, start + 40, checkpointId);
    FormatBytes.putLong(target, start + 48, commitSequence);
    FormatBytes.putLong(target, start + 56, maximumTransactionId);
    FormatBytes.putLong(target, start + 64, maximumLogicalRowId);
    FormatBytes.putLong(target, start + 72, maximumVersionId);
    FormatBytes.putInt(target, start + 80, nextPageId);
    FormatBytes.putInt(target, start + 84, rootDirectoryPageId);
    FormatBytes.putInt(target, start + 88, logicalDirectoryPageId);
    FormatBytes.putInt(target, start + 92, versionDirectoryPageId);
    FormatBytes.putInt(target, start + 96, freePageRootId);
    FormatBytes.putInt(target, start + 100, extentCount);
    FormatBytes.putLong(target, start + 104, rootDirectoryGeneration);
    FormatBytes.putLong(target, start + 112, logicalDirectoryGeneration);
    FormatBytes.putLong(target, start + 120, versionDirectoryGeneration);
    FormatBytes.putLong(target, start + 128, freePageGeneration);
    FormatBytes.putLong(target, start + 136, storageGeneration);
    return StatusCode.OK;
  }

  public static StatusCode encodeExtent(
      ByteBuffer target,
      int start,
      int extentIndex,
      int firstPageId,
      int pageCount,
      long flushGeneration) {
    if (target == null
        || target.isReadOnly()
        || start < 0
        || target.limit() - start < BYTES
        || FormatBytes.getLong(target, start) != MAGIC
        || FormatBytes.getInt(target, start + 8) != VERSION
        || FormatBytes.getInt(target, start + 12) != BYTES
        || !validHeaderFrom(target, start)
        || extentIndex < 0
        || extentIndex >= FormatBytes.getInt(target, start + 100)
        || firstPageId <= 0
        || pageCount <= 0
        || firstPageId > FormatBytes.getInt(target, start + 80) - pageCount
        || flushGeneration <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int offset = start + EXTENTS_OFFSET + extentIndex * EXTENT_BYTES;
    FormatBytes.putInt(target, offset, firstPageId);
    FormatBytes.putInt(target, offset + 4, pageCount);
    FormatBytes.putLong(target, offset + 8, flushGeneration);
    return StatusCode.OK;
  }

  public static StatusCode seal(ByteBuffer target, int start, CRC32C checksum) {
    if (target == null
        || target.isReadOnly()
        || checksum == null
        || start < 0
        || target.limit() - start < BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (FormatBytes.getLong(target, start) != MAGIC
        || FormatBytes.getInt(target, start + 8) != VERSION
        || FormatBytes.getInt(target, start + 12) != BYTES
        || !validHeaderFrom(target, start)
        || !validExtents(target, start)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int value = FormatBytes.checksum(target, start, CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(target, start + CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + CHECKSUM_OFFSET + 4, ~value);
    return StatusCode.OK;
  }

  public static StatusCode decode(
      ByteBuffer source, int start, CheckpointManifest result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null
        || checksum == null
        || start < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source.limit() - start != BYTES) return StatusCode.CORRUPTION;
    int stored = FormatBytes.getInt(source, start + CHECKSUM_OFFSET);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + 12) != BYTES
        || FormatBytes.getInt(source, start + CHECKSUM_OFFSET + 4) != ~stored
        || FormatBytes.checksum(source, start, CHECKSUM_OFFSET, checksum) != stored
        || !validHeaderFrom(source, start)
        || !validExtents(source, start)) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        FormatBytes.getLong(source, start + 16),
        FormatBytes.getLong(source, start + 24),
        FormatBytes.getLong(source, start + 32),
        FormatBytes.getLong(source, start + 40),
        FormatBytes.getLong(source, start + 48),
        FormatBytes.getLong(source, start + 56),
        FormatBytes.getLong(source, start + 64),
        FormatBytes.getLong(source, start + 72),
        FormatBytes.getInt(source, start + 80),
        FormatBytes.getInt(source, start + 84),
        FormatBytes.getInt(source, start + 88),
        FormatBytes.getInt(source, start + 92),
        FormatBytes.getInt(source, start + 96),
        FormatBytes.getInt(source, start + 100),
        FormatBytes.getLong(source, start + 104),
        FormatBytes.getLong(source, start + 112),
        FormatBytes.getLong(source, start + 120),
        FormatBytes.getLong(source, start + 128),
        FormatBytes.getLong(source, start + 136));
    return StatusCode.OK;
  }

  public static StatusCode decodeExtent(
      ByteBuffer source,
      int start,
      CheckpointManifest manifest,
      int extentIndex,
      CheckpointExtent result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null
        || manifest == null
        || start < 0
        || source.limit() - start != BYTES
        || extentIndex < 0
        || extentIndex >= manifest.extentCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int offset = start + EXTENTS_OFFSET + extentIndex * EXTENT_BYTES;
    result.set(
        FormatBytes.getInt(source, offset),
        FormatBytes.getInt(source, offset + 4),
        FormatBytes.getLong(source, offset + 8));
    return StatusCode.OK;
  }

  private static boolean validHeaderFrom(ByteBuffer source, int start) {
    if (!zeroRange(source, start + 144, start + HEADER_BYTES)) return false;
    return validHeader(
        FormatBytes.getLong(source, start + 16),
        FormatBytes.getLong(source, start + 24),
        FormatBytes.getLong(source, start + 32),
        FormatBytes.getLong(source, start + 40),
        FormatBytes.getLong(source, start + 48),
        FormatBytes.getLong(source, start + 56),
        FormatBytes.getLong(source, start + 64),
        FormatBytes.getLong(source, start + 72),
        FormatBytes.getInt(source, start + 80),
        FormatBytes.getInt(source, start + 84),
        FormatBytes.getInt(source, start + 88),
        FormatBytes.getInt(source, start + 92),
        FormatBytes.getInt(source, start + 96),
        FormatBytes.getInt(source, start + 100),
        FormatBytes.getLong(source, start + 104),
        FormatBytes.getLong(source, start + 112),
        FormatBytes.getLong(source, start + 120),
        FormatBytes.getLong(source, start + 128),
        FormatBytes.getLong(source, start + 136));
  }

  private static boolean validHeader(
      long databaseHigh,
      long databaseLow,
      long walGeneration,
      long checkpointId,
      long commitSequence,
      long maximumTransactionId,
      long maximumLogicalRowId,
      long maximumVersionId,
      int nextPageId,
      int rootDirectoryPageId,
      int logicalDirectoryPageId,
      int versionDirectoryPageId,
      int freePageRootId,
      int extentCount,
      long rootDirectoryGeneration,
      long logicalDirectoryGeneration,
      long versionDirectoryGeneration,
      long freePageGeneration,
      long storageGeneration) {
    int maximumRoot = Math.max(
        Math.max(rootDirectoryPageId, logicalDirectoryPageId),
        Math.max(versionDirectoryPageId, freePageRootId));
    return (databaseHigh != 0 || databaseLow != 0)
        && walGeneration > 0
        && checkpointId > 0
        && commitSequence > 0
        && maximumTransactionId > 0
        && maximumLogicalRowId >= 0
        && maximumVersionId >= 0
        && nextPageId > 1
        && maximumRoot > 0
        && maximumRoot < nextPageId
        && rootDirectoryPageId > 0
        && logicalDirectoryPageId > 0
        && versionDirectoryPageId > 0
        && freePageRootId > 0
        && rootDirectoryPageId != logicalDirectoryPageId
        && rootDirectoryPageId != versionDirectoryPageId
        && rootDirectoryPageId != freePageRootId
        && logicalDirectoryPageId != versionDirectoryPageId
        && logicalDirectoryPageId != freePageRootId
        && versionDirectoryPageId != freePageRootId
        && extentCount >= 0
        && extentCount <= MAXIMUM_EXTENTS
        && rootDirectoryGeneration > 0
        && logicalDirectoryGeneration > 0
        && versionDirectoryGeneration > 0
        && freePageGeneration > 0
        && storageGeneration > 0;
  }

  private static boolean validExtents(ByteBuffer source, int start) {
    int count = FormatBytes.getInt(source, start + 100);
    int nextPage = FormatBytes.getInt(source, start + 80);
    int previousEnd = 0;
    for (int index = 0; index < MAXIMUM_EXTENTS; index++) {
      int offset = start + EXTENTS_OFFSET + index * EXTENT_BYTES;
      int first = FormatBytes.getInt(source, offset);
      int pages = FormatBytes.getInt(source, offset + 4);
      long generation = FormatBytes.getLong(source, offset + 8);
      if (index < count) {
        if (first <= 0
            || pages <= 0
            || first < previousEnd
            || first > nextPage - pages
            || generation <= 0) {
          return false;
        }
        previousEnd = first + pages;
      } else if (first != 0 || pages != 0 || generation != 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean zeroRange(ByteBuffer source, int start, int end) {
    for (int index = start; index < end; index++) {
      if (source.get(index) != 0) return false;
    }
    return true;
  }
}
