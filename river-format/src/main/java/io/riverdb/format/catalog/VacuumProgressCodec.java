package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Checksummed v1 progress authority for bounded generational vacuum. */
public final class VacuumProgressCodec {
  public static final int VERSION = 1;
  public static final int STATE_BUILDING = 1;
  public static final int STATE_COMPLETE = 2;
  public static final int BYTES = 176;

  private static final long MAGIC = 0x5249565641435031L; // RIVVACP1
  private static final int CHECKSUM_OFFSET = 168;

  private VacuumProgressCodec() {
  }

  /**
   * Encodes the value for catalog sequence-space key
   * {@link CatalogContinuationKey#VACUUM_PROGRESS_KEY}.
   */
  public static StatusCode encode(
      ByteBuffer target,
      int start,
      int state,
      long sourceStorageGeneration,
      long replacementStorageGeneration,
      long sourceMaximumLogicalRowId,
      long lastCopiedLogicalRowId,
      int sourceRootDirectoryPageId,
      int replacementRootDirectoryPageId,
      int replacementLogicalDirectoryPageId,
      int replacementVersionDirectoryPageId,
      int replacementFreePageRootId,
      int nextPageId,
      long sourceRootDirectoryGeneration,
      long replacementRootDirectoryGeneration,
      long replacementLogicalDirectoryGeneration,
      long replacementVersionDirectoryGeneration,
      long replacementFreePageGeneration,
      long rowsCopied,
      long versionsReclaimed,
      long progressGeneration,
      long sourceCommitSequence,
      long appliedCommitSequence,
      CRC32C checksum) {
    if (target == null
        || target.isReadOnly()
        || checksum == null
        || start < 0
        || target.limit() - start < BYTES
        || !valid(
            state,
            sourceStorageGeneration,
            replacementStorageGeneration,
            sourceMaximumLogicalRowId,
            lastCopiedLogicalRowId,
            sourceRootDirectoryPageId,
            replacementRootDirectoryPageId,
            replacementLogicalDirectoryPageId,
            replacementVersionDirectoryPageId,
            replacementFreePageRootId,
            nextPageId,
            sourceRootDirectoryGeneration,
            replacementRootDirectoryGeneration,
            replacementLogicalDirectoryGeneration,
            replacementVersionDirectoryGeneration,
            replacementFreePageGeneration,
            rowsCopied,
            versionsReclaimed,
            progressGeneration,
            sourceCommitSequence,
            appliedCommitSequence)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = start; index < start + BYTES; index++) target.put(index, (byte) 0);
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, BYTES);
    FormatBytes.putInt(target, start + 16, state);
    FormatBytes.putLong(target, start + 24, sourceStorageGeneration);
    FormatBytes.putLong(target, start + 32, replacementStorageGeneration);
    FormatBytes.putLong(target, start + 40, sourceMaximumLogicalRowId);
    FormatBytes.putLong(target, start + 48, lastCopiedLogicalRowId);
    FormatBytes.putInt(target, start + 56, sourceRootDirectoryPageId);
    FormatBytes.putInt(target, start + 60, replacementRootDirectoryPageId);
    FormatBytes.putInt(target, start + 64, replacementLogicalDirectoryPageId);
    FormatBytes.putInt(target, start + 68, replacementVersionDirectoryPageId);
    FormatBytes.putInt(target, start + 72, replacementFreePageRootId);
    FormatBytes.putInt(target, start + 76, nextPageId);
    FormatBytes.putLong(target, start + 80, sourceRootDirectoryGeneration);
    FormatBytes.putLong(target, start + 88, replacementRootDirectoryGeneration);
    FormatBytes.putLong(target, start + 96, replacementLogicalDirectoryGeneration);
    FormatBytes.putLong(target, start + 104, replacementVersionDirectoryGeneration);
    FormatBytes.putLong(target, start + 112, replacementFreePageGeneration);
    FormatBytes.putLong(target, start + 120, rowsCopied);
    FormatBytes.putLong(target, start + 128, versionsReclaimed);
    FormatBytes.putLong(target, start + 136, progressGeneration);
    FormatBytes.putLong(target, start + 144, sourceCommitSequence);
    FormatBytes.putLong(target, start + 152, appliedCommitSequence);
    int value = FormatBytes.checksum(target, start, CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(target, start + CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + CHECKSUM_OFFSET + 4, ~value);
    return StatusCode.OK;
  }

  public static StatusCode decode(
      ByteBuffer source, int start, VacuumProgress result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || checksum == null || start < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source.limit() - start != BYTES) return StatusCode.CORRUPTION;
    int state = FormatBytes.getInt(source, start + 16);
    int stored = FormatBytes.getInt(source, start + CHECKSUM_OFFSET);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + 12) != BYTES
        || FormatBytes.getInt(source, start + 20) != 0
        || FormatBytes.getLong(source, start + 160) != 0
        || FormatBytes.getInt(source, start + CHECKSUM_OFFSET + 4) != ~stored
        || FormatBytes.checksum(source, start, CHECKSUM_OFFSET, checksum) != stored
        || !valid(
            state,
            FormatBytes.getLong(source, start + 24),
            FormatBytes.getLong(source, start + 32),
            FormatBytes.getLong(source, start + 40),
            FormatBytes.getLong(source, start + 48),
            FormatBytes.getInt(source, start + 56),
            FormatBytes.getInt(source, start + 60),
            FormatBytes.getInt(source, start + 64),
            FormatBytes.getInt(source, start + 68),
            FormatBytes.getInt(source, start + 72),
            FormatBytes.getInt(source, start + 76),
            FormatBytes.getLong(source, start + 80),
            FormatBytes.getLong(source, start + 88),
            FormatBytes.getLong(source, start + 96),
            FormatBytes.getLong(source, start + 104),
            FormatBytes.getLong(source, start + 112),
            FormatBytes.getLong(source, start + 120),
            FormatBytes.getLong(source, start + 128),
            FormatBytes.getLong(source, start + 136),
            FormatBytes.getLong(source, start + 144),
            FormatBytes.getLong(source, start + 152))) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        state,
        FormatBytes.getLong(source, start + 24),
        FormatBytes.getLong(source, start + 32),
        FormatBytes.getLong(source, start + 40),
        FormatBytes.getLong(source, start + 48),
        FormatBytes.getInt(source, start + 56),
        FormatBytes.getInt(source, start + 60),
        FormatBytes.getInt(source, start + 64),
        FormatBytes.getInt(source, start + 68),
        FormatBytes.getInt(source, start + 72),
        FormatBytes.getInt(source, start + 76),
        FormatBytes.getLong(source, start + 80),
        FormatBytes.getLong(source, start + 88),
        FormatBytes.getLong(source, start + 96),
        FormatBytes.getLong(source, start + 104),
        FormatBytes.getLong(source, start + 112),
        FormatBytes.getLong(source, start + 120),
        FormatBytes.getLong(source, start + 128),
        FormatBytes.getLong(source, start + 136),
        FormatBytes.getLong(source, start + 144),
        FormatBytes.getLong(source, start + 152));
    return StatusCode.OK;
  }

  private static boolean valid(
      int state,
      long sourceStorage,
      long replacementStorage,
      long sourceMaximumLogical,
      long lastCopiedLogical,
      int sourceRoot,
      int replacementRoot,
      int replacementLogical,
      int replacementVersion,
      int replacementFree,
      int nextPage,
      long sourceRootGeneration,
      long replacementRootGeneration,
      long replacementLogicalGeneration,
      long replacementVersionGeneration,
      long replacementFreeGeneration,
      long rowsCopied,
      long versionsReclaimed,
      long progressGeneration,
      long sourceCommitSequence,
      long appliedCommitSequence) {
    return (state == STATE_BUILDING || state == STATE_COMPLETE)
        && sourceStorage > 0
        && replacementStorage > sourceStorage
        && sourceMaximumLogical >= 0
        && lastCopiedLogical >= 0
        && lastCopiedLogical <= sourceMaximumLogical
        && (state != STATE_COMPLETE || lastCopiedLogical == sourceMaximumLogical)
        && sourceRoot > 0
        && replacementRoot > 0
        && replacementLogical > 0
        && replacementVersion > 0
        && replacementFree > 0
        && nextPage > 1
        && sourceRoot < nextPage
        && replacementRoot < nextPage
        && replacementLogical < nextPage
        && replacementVersion < nextPage
        && replacementFree < nextPage
        && distinct(
            sourceRoot,
            replacementRoot,
            replacementLogical,
            replacementVersion,
            replacementFree)
        && sourceRootGeneration > 0
        && replacementRootGeneration > 0
        && replacementLogicalGeneration > 0
        && replacementVersionGeneration > 0
        && replacementFreeGeneration > 0
        && rowsCopied >= 0
        && rowsCopied <= lastCopiedLogical
        && versionsReclaimed >= 0
        && progressGeneration > 0
        && sourceCommitSequence > 0
        && appliedCommitSequence > 0
        && appliedCommitSequence <= sourceCommitSequence
        && (state != STATE_COMPLETE || appliedCommitSequence == sourceCommitSequence);
  }

  private static boolean distinct(int first, int second, int third, int fourth, int fifth) {
    return first != second && first != third && first != fourth && first != fifth
        && second != third && second != fourth && second != fifth
        && third != fourth && third != fifth && fourth != fifth;
  }
}
