package io.riverdb.engine.checkpoint;

import java.nio.ByteBuffer;

/** Header and index codec for one immutable sparse version generation. */
final class CheckpointVersionGenerationFormat {
  static final long MAGIC = 0x5249565644495233L; // RIVVDIR3
  static final int HEADER_BYTES = 128;
  static final int INDEX_ENTRY_BYTES = 16;

  private CheckpointVersionGenerationFormat() {
  }

  static void encodeHeader(
      ByteBuffer header, ByteBuffer index, CheckpointState state,
      int pages, long dataOffset, long fileBytes, CheckpointChecksum checksum) {
    CheckpointVersionFormat.zero(header, HEADER_BYTES);
    header.putLong(0, MAGIC);
    header.putInt(8, CheckpointVersionFormat.VERSION);
    header.putInt(12, HEADER_BYTES);
    header.putLong(16, state.checkpointId());
    header.putLong(24, state.rowCount());
    header.putLong(32, state.commitSequence());
    header.putInt(40, pages);
    header.putInt(44, CheckpointVersionFormat.PAGE_ROWS);
    header.putLong(48, HEADER_BYTES);
    header.putLong(56, dataOffset);
    header.putLong(64, fileBytes);
    int indexChecksum = checksum.value(index, pages * INDEX_ENTRY_BYTES, -1);
    header.putInt(72, indexChecksum);
    header.putInt(76, ~indexChecksum);
    header.putLong(80, state.database().high());
    header.putLong(88, state.database().low());
    header.putLong(96, state.walGeneration().value());
    int headerChecksum = checksum.value(header, HEADER_BYTES);
    header.putInt(120, headerChecksum);
    header.putInt(124, ~headerChecksum);
  }

  static boolean validHeader(
      ByteBuffer header, CheckpointState state, int pages, long fileBytes,
      CheckpointChecksum checksum) {
    int stored = header.getInt(120);
    int indexBytes = pages * INDEX_ENTRY_BYTES;
    return header.getLong(0) == MAGIC
        && header.getInt(8) == CheckpointVersionFormat.VERSION
        && header.getInt(12) == HEADER_BYTES
        && header.getLong(16) == state.checkpointId()
        && header.getLong(24) == state.rowCount()
        && header.getLong(32) == state.commitSequence()
        && header.getInt(40) == pages
        && header.getInt(44) == CheckpointVersionFormat.PAGE_ROWS
        && header.getLong(48) == HEADER_BYTES
        && header.getLong(56) >= HEADER_BYTES + indexBytes
        && header.getLong(64) == fileBytes
        && header.getLong(80) == state.database().high()
        && header.getLong(88) == state.database().low()
        && header.getLong(96) == state.walGeneration().value()
        && header.getInt(124) == ~stored
        && checksum.value(header, HEADER_BYTES) == stored
        && CheckpointVersionFormat.zeroRange(header, 104, 120);
  }

  static boolean validIndex(
      ByteBuffer header, ByteBuffer index, CheckpointState state,
      long[] pageIds, long[] offsets, CheckpointChecksum checksum) {
    int pages = pageIds.length;
    int bytes = pages * INDEX_ENTRY_BYTES;
    int stored = header.getInt(72);
    if (header.getInt(76) != ~stored
        || checksum.value(index, bytes, -1) != stored) return false;
    long dataOffset = header.getLong(56);
    long previous = -1;
    for (int slot = 0; slot < pages; slot++) {
      long pageId = index.getLong(slot * INDEX_ENTRY_BYTES);
      long offset = index.getLong(slot * INDEX_ENTRY_BYTES + Long.BYTES);
      if (pageId <= previous || pageId > (state.rowCount() - 1)
          >>> CheckpointVersionFormat.PAGE_SHIFT
          || offset != dataOffset + (long) slot * CheckpointVersionFormat.SEGMENT_BYTES) {
        return false;
      }
      pageIds[slot] = pageId;
      offsets[slot] = offset;
      previous = pageId;
    }
    return dataOffset + (long) pages * CheckpointVersionFormat.SEGMENT_BYTES
        == header.getLong(64);
  }
}
