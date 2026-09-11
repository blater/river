package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;

/** Validates fixed response fields and delegates variable values to the value decoder. */
final class ProtocolResponsePayloadDecoder {
  private static final int FIXED_BYTES = 64;
  private ProtocolResponsePayloadDecoder() { }

  static StatusCode decode(ProtocolFrame frame, ProtocolResponse result) {
    int offset = frame.payloadOffset();
    java.nio.ByteBuffer bytes = frame.source();
    StatusCode status = ProtocolStableStatus.fromCode(bytes.getInt(offset));
    int flags = bytes.getInt(offset + 4);
    int rows = bytes.getInt(offset + 8);
    int columns = bytes.getInt(offset + 12);
    long commitSequence = bytes.getLong(offset + 16);
    long key = bytes.getLong(offset + 24);
    long returned = bytes.getLong(offset + 32);
    long challengeHigh = bytes.getLong(offset + 40);
    long challengeLow = bytes.getLong(offset + 48);
    int nullBytes = bytes.getInt(offset + 56);
    int reserved = bytes.getInt(offset + 60);
    if (!ProtocolResponseAdmission.validFixed(frame, status, flags, rows, columns,
        commitSequence, key, returned, nullBytes, reserved)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean metadata = (flags & ProtocolFrameCodec.FLAG_COLUMN_METADATA) != 0;
    boolean row = (flags & ProtocolFrameCodec.FLAG_ROW_AVAILABLE) != 0;
    int variableBytes = frame.payloadBytes() - FIXED_BYTES;
    StatusCode admitted = result.reserve(
        columns,
        metadata ? row ? variableBytes - reserved - nullBytes : 0 : variableBytes,
        metadata ? reserved : 0);
    if (!admitted.isOk()) return admitted;
    result.complete(status, flags, rows, columns, commitSequence, key, returned,
        challengeHigh, challengeLow);
    int end = offset + frame.payloadBytes();
    if (metadata) {
      return ProtocolQueryOpenResponseDecoder.decode(bytes, offset + FIXED_BYTES, end, columns,
          nullBytes, reserved, row, result);
    }
    admitted = ProtocolResponseNullBitmap.decode(
        bytes, offset + FIXED_BYTES, columns, nullBytes, result);
    if (!admitted.isOk()) return admitted;
    int valueOffset = ProtocolResponseValueDecoder.types(
        bytes, offset + FIXED_BYTES + nullBytes, end, columns, result);
    if (valueOffset < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    return ProtocolResponseValueDecoder.values(bytes, valueOffset, end, columns, result);
  }
}
