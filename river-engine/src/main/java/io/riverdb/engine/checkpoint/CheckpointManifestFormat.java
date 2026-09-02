package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import java.nio.ByteBuffer;

/** Fixed v4 authority-manifest codec binding page, version, and logical-ID generations. */
final class CheckpointManifestFormat {
  static final int BYTES = 160;
  private static final int VERSION = 4;
  private static final long MAGIC = 0x5249564552434b50L; // RIVERCKP
  private static final long HAS_VERSIONS = 1;
  private static final long HAS_LOGICAL_ROW_IDS = 2;

  private CheckpointManifestFormat() {
  }

  static void encode(
      ByteBuffer bytes, CheckpointState state, int versionPages, long versionBytes,
      int versionSlot, int cleanupSlot,
      CheckpointLogicalRowIdManifestReference logicalRowIds,
      CheckpointChecksum checksum) {
    CheckpointVersionFormat.zero(bytes, BYTES);
    bytes.putLong(0, MAGIC);
    bytes.putInt(8, VERSION);
    bytes.putInt(12, BYTES);
    bytes.putLong(16, state.database().high());
    bytes.putLong(24, state.database().low());
    bytes.putLong(32, state.walGeneration().value());
    bytes.putLong(40, state.checkpointId());
    bytes.putLong(48, state.commitSequence());
    bytes.putLong(56, state.maximumTransactionId());
    bytes.putInt(64, state.pageCount());
    bytes.putInt(68, versionPages);
    bytes.putLong(72, state.rowCount());
    bytes.putLong(80, state.obsoleteVersionCount());
    long flags = HAS_LOGICAL_ROW_IDS;
    if (versionPages > 0) flags |= HAS_VERSIONS;
    bytes.putLong(88, flags);
    bytes.putLong(96, versionBytes);
    bytes.putInt(104, encodeSlot(versionSlot));
    bytes.putInt(108, encodeSlot(cleanupSlot));
    bytes.putInt(112, logicalRowIds.count());
    bytes.putInt(116, logicalRowIds.digest());
    bytes.putLong(120, logicalRowIds.fileBytes());
    bytes.putInt(128, encodeSlot(logicalRowIds.slot()));
    bytes.putInt(132, encodeSlot(logicalRowIds.cleanupSlot()));
    int value = checksum.value(bytes, BYTES);
    bytes.putInt(152, value);
    bytes.putInt(156, ~value);
  }

  static StatusCode decode(
      ByteBuffer bytes, CheckpointState state, CheckpointManifestVersion versions,
      CheckpointLogicalRowIdManifestReference logicalRowIds,
      CheckpointChecksum checksum) {
    if (!valid(bytes, checksum)) return StatusCode.CORRUPTION;
    StatusCode status = state.setLarge(
        DatabaseIncarnation.of(bytes.getLong(16), bytes.getLong(24)),
        WalGeneration.of(bytes.getLong(32)), bytes.getLong(40), bytes.getLong(48),
        bytes.getLong(56), bytes.getInt(64), bytes.getLong(72));
    if (!status.isOk()) return StatusCode.CORRUPTION;
    state.setObsoleteVersionCount(bytes.getLong(80));
    versions.set(
        bytes.getInt(68), bytes.getLong(96),
        decodeSlot(bytes.getInt(104)), decodeSlot(bytes.getInt(108)));
    logicalRowIds.set(
        bytes.getInt(112), bytes.getLong(120), bytes.getInt(116),
        decodeSlot(bytes.getInt(128)), decodeSlot(bytes.getInt(132)));
    return StatusCode.OK;
  }

  private static boolean valid(ByteBuffer bytes, CheckpointChecksum checksum) {
    int stored = bytes.getInt(152);
    int versionPages = bytes.getInt(68);
    long flags = bytes.getLong(88);
    long versionBytes = bytes.getLong(96);
    long rowCount = bytes.getLong(72);
    long maximumVersionPages = rowCount == 0 ? 0
        : ((rowCount - 1) >>> CheckpointVersionFormat.PAGE_SHIFT) + 1;
    int versionSlot = decodeSlot(bytes.getInt(104));
    int cleanupSlot = decodeSlot(bytes.getInt(108));
    int logicalCount = bytes.getInt(112);
    long logicalBytes = bytes.getLong(120);
    int logicalSlot = decodeSlot(bytes.getInt(128));
    int logicalCleanupSlot = decodeSlot(bytes.getInt(132));
    long expectedLogicalBytes = CheckpointLogicalRowIdFormat.fileBytes(logicalCount);
    return bytes.getLong(0) == MAGIC
        && bytes.getInt(8) == VERSION
        && bytes.getInt(12) == BYTES && bytes.getInt(156) == ~stored
        && checksum.value(bytes, BYTES) == stored
        && (bytes.getLong(16) != 0 || bytes.getLong(24) != 0)
        && bytes.getLong(32) > 0 && bytes.getLong(40) > 0
        && bytes.getLong(48) > 0 && bytes.getLong(56) > 0
        && bytes.getInt(64) > 0 && versionPages >= 0
        && rowCount >= 0 && rowCount <= CheckpointState.MAXIMUM_RUNTIME_ROWS
        && versionPages <= maximumVersionPages
        && bytes.getLong(80) >= 0
        && (flags == HAS_LOGICAL_ROW_IDS
            || flags == (HAS_VERSIONS | HAS_LOGICAL_ROW_IDS))
        && (versionPages == 0) == ((flags & HAS_VERSIONS) == 0)
        && (versionPages == 0) == (versionBytes == 0)
        && (versionPages == 0) == (versionSlot < 0)
        && bytes.getInt(104) >= 0 && bytes.getInt(104) <= 2
        && bytes.getInt(108) >= 0 && bytes.getInt(108) <= 2
        && (cleanupSlot < 0 || cleanupSlot != versionSlot)
        && logicalCount >= 0 && expectedLogicalBytes >= 0
        && logicalBytes == expectedLogicalBytes
        && logicalSlot >= 0
        && bytes.getInt(128) >= 1 && bytes.getInt(128) <= 2
        && bytes.getInt(132) >= 0 && bytes.getInt(132) <= 2
        && (logicalCleanupSlot < 0 || logicalCleanupSlot != logicalSlot)
        && CheckpointVersionFormat.zeroRange(bytes, 136, 152);
  }

  private static int encodeSlot(int slot) { return slot + 1; }
  private static int decodeSlot(int encoded) { return encoded - 1; }
}
