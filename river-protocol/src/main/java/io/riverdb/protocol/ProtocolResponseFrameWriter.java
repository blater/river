package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;

/** Writes the common fixed fields and value body of a response frame. */
final class ProtocolResponseFrameWriter {
  static final int FIXED_BYTES = 64;

  private ProtocolResponseFrameWriter() { }

  static StatusCode encode(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      int flags,
      int rows,
      int columns,
      long commitSequence,
      long key,
      long returned,
      long challengeHigh,
      long challengeLow,
      CommandResult command,
      RowResult row) {
    if (status == null || columns < 0 || columns > CommandResult.MAXIMUM_COLUMNS) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int valueBytes = ProtocolResponseValueEncoder.bytes(command, row, columns);
    if (valueBytes < 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int nullBitmapBytes = ProtocolResponseValueEncoder.nullBitmapBytes(columns);
    int payloadBytes = FIXED_BYTES + nullBitmapBytes
        + columns * Integer.BYTES + valueBytes;
    StatusCode encoded = ProtocolFrameWire.begin(
        target, type, requestId, payloadBytes, ProtocolFrameWire.FRAME_RESPONSE);
    if (!encoded.isOk()) {
      return encoded;
    }
    writeFixed(
        target, status, flags, rows, columns, commitSequence, key, returned,
        challengeHigh, challengeLow, nullBitmapBytes, 0);
    int offset = ProtocolResponseValueEncoder.writeNulls(
        target, ProtocolFrameCodec.HEADER_BYTES + FIXED_BYTES,
        command, row, columns);
    offset = ProtocolResponseValueEncoder.writeTypes(
        target, offset, command, row, columns);
    if (!ProtocolResponseValueEncoder.writeValues(
        target, offset, command, row, columns)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    return ProtocolResponseSegmenter.finish(target, type, requestId, payloadBytes);
  }

  static void writeFixed(
      ByteBuffer target,
      StatusCode status,
      int flags,
      int rows,
      int columns,
      long commitSequence,
      long key,
      long returned,
      long challengeHigh,
      long challengeLow,
      int nullBitmapBytes,
      int metadataBytes) {
    int offset = ProtocolFrameCodec.HEADER_BYTES;
    target.putInt(offset, status.stableCode());
    target.putInt(offset + 4, flags);
    target.putInt(offset + 8, rows);
    target.putInt(offset + 12, columns);
    target.putLong(offset + 16, commitSequence);
    target.putLong(offset + 24, key);
    target.putLong(offset + 32, returned);
    target.putLong(offset + 40, challengeHigh);
    target.putLong(offset + 48, challengeLow);
    target.putInt(offset + 56, nullBitmapBytes);
    target.putInt(offset + 60, metadataBytes);
  }
}
