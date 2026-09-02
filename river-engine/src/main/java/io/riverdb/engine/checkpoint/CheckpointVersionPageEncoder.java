package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reusable encoder for one immutable sparse version page. */
final class CheckpointVersionPageEncoder {
  private final ByteBuffer bytes = ByteBuffer
      .allocateDirect(CheckpointVersionFormat.SEGMENT_BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
  private final CheckpointVersionResult version = new CheckpointVersionResult();
  private final CheckpointChecksum checksum = new CheckpointChecksum();
  private boolean exceptions;

  StatusCode encode(CheckpointState state, long pageId) {
    CheckpointVersionFormat.zero(bytes, CheckpointVersionFormat.SEGMENT_BYTES);
    encodeHeader(state, pageId);
    exceptions = false;
    long firstRowId = (pageId << CheckpointVersionFormat.PAGE_SHIFT) + 1;
    long lastRowId = Math.min(
        state.rowCount(), firstRowId + CheckpointVersionFormat.PAGE_ROWS - 1);
    for (long rowId = firstRowId; rowId <= lastRowId; rowId++) {
      StatusCode status = encodeRecord(state, firstRowId, rowId);
      if (!status.isOk()) return status;
    }
    int checksumOffset = CheckpointVersionFormat.SEGMENT_BYTES - 8;
    int value = checksum.value(bytes, CheckpointVersionFormat.SEGMENT_BYTES);
    bytes.putInt(checksumOffset, value);
    bytes.putInt(checksumOffset + 4, ~value);
    return StatusCode.OK;
  }

  ByteBuffer bytes() {
    bytes.position(0);
    bytes.limit(CheckpointVersionFormat.SEGMENT_BYTES);
    return bytes;
  }

  boolean hasExceptions() { return exceptions; }

  private StatusCode encodeRecord(CheckpointState state, long firstRowId, long rowId) {
    StatusCode status = state.readVersion(rowId, version);
    if (!status.isOk()) return status;
    if (version.commitSequence() == state.commitSequence()
        && version.previousRowId() == 0 && !version.deleted()) return StatusCode.OK;
    int offset = CheckpointVersionFormat.SEGMENT_HEADER_BYTES
        + (int) (rowId - firstRowId) * CheckpointVersionFormat.RECORD_BYTES;
    bytes.putLong(offset, version.commitSequence());
    bytes.putLong(offset + Long.BYTES, version.previousRowId());
    bytes.putLong(offset + Long.BYTES * 2, version.deleted() ? 1 : 0);
    exceptions = true;
    return StatusCode.OK;
  }

  private void encodeHeader(CheckpointState state, long pageId) {
    bytes.putLong(0, CheckpointVersionFormat.SEGMENT_MAGIC);
    bytes.putInt(8, CheckpointVersionFormat.VERSION);
    bytes.putInt(12, CheckpointVersionFormat.SEGMENT_HEADER_BYTES);
    bytes.putLong(16, state.checkpointId());
    bytes.putLong(24, pageId);
    bytes.putLong(32, state.rowCount());
    bytes.putInt(40, CheckpointVersionFormat.PAGE_ROWS);
    bytes.putLong(48, state.database().high());
    bytes.putLong(56, state.database().low());
    bytes.putLong(64, state.walGeneration().value());
    bytes.putLong(72, state.commitSequence());
  }
}
