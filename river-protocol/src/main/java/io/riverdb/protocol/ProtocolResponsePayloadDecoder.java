package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.ParameterSet;

/** Validates fixed response fields and delegates variable values to the value decoder. */
final class ProtocolResponsePayloadDecoder {
  private static final int FIXED_BYTES = 64;
  private static final int VALID_FLAGS = ProtocolFrameCodec.FLAG_ROW_AVAILABLE
      | ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE | ProtocolFrameCodec.FLAG_QUERY_ACTIVE
      | ProtocolFrameCodec.FLAG_COLUMN_METADATA | ProtocolFrameCodec.FLAG_PREPARED_QUERY
      | ProtocolFrameCodec.FLAG_END_OF_STREAM;

  private ProtocolResponsePayloadDecoder() { }

  static StatusCode decode(ProtocolFrame frame, ProtocolResponse result) {
    int offset = frame.payloadOffset();
    java.nio.ByteBuffer bytes = frame.source();
    StatusCode status = statusFromStableCode(bytes.getInt(offset));
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
    if (!validFixed(frame, status, flags, rows, columns, commitSequence, key, returned,
        nullBytes, reserved)) {
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
      return decodeQueryOpen(bytes, offset + FIXED_BYTES, end, columns,
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

  private static StatusCode decodeQueryOpen(
      java.nio.ByteBuffer bytes,
      int offset,
      int end,
      int columns,
      int rowNullBytes,
      int metadataBytes,
      boolean row,
      ProtocolResponse result) {
    int nullableBytes = ProtocolResponseNullBitmap.bytes(columns);
    int metadataEnd = offset + metadataBytes;
    StatusCode status = ProtocolResponseNullBitmap.decodeNullable(
        bytes, offset, columns, nullableBytes, result);
    if (!status.isOk()) return status;
    int nameOffset = ProtocolResponseValueDecoder.types(
        bytes, offset + nullableBytes, metadataEnd, columns, result);
    if (nameOffset < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    status = ProtocolResponseValueDecoder.metadata(
        bytes, nameOffset, metadataEnd, columns, result);
    if (!status.isOk()) return status;
    if (!row) return result.beginNulls(columns);
    status = ProtocolResponseNullBitmap.decode(
        bytes, metadataEnd, columns, rowNullBytes, result);
    return status.isOk()
        ? ProtocolResponseValueDecoder.values(
            bytes, metadataEnd + rowNullBytes, end, columns, result)
        : status;
  }

  private static boolean validFixed(ProtocolFrame frame, StatusCode status, int flags, int rows,
      int columns, long commitSequence, long key, long returned, int nullBytes, int reserved) {
    if (status == null || (flags & ~VALID_FLAGS) != 0 || rows < 0 || columns < 0
        || columns > CommandResult.MAXIMUM_COLUMNS || commitSequence < 0 || returned < 0) {
      return false;
    }
    boolean metadata = (flags & ProtocolFrameCodec.FLAG_COLUMN_METADATA) != 0;
    boolean preparedQuery = (flags & ProtocolFrameCodec.FLAG_PREPARED_QUERY) != 0;
    if (frame.type() == ProtocolMessageType.PREPARE) {
      return status.isOk()
          ? (flags == 0 || flags == ProtocolFrameCodec.FLAG_PREPARED_QUERY)
              && rows <= ParameterSet.MAXIMUM_PARAMETERS
              && columns == 0 && commitSequence == 0 && key > 0 && returned == 0
          : flags == 0 && rows == 0 && columns == 0 && commitSequence == 0
              && key == 0 && returned == 0;
    }
    if (metadata) {
      boolean row = (flags & ProtocolFrameCodec.FLAG_ROW_AVAILABLE) != 0;
      boolean active = (flags & ProtocolFrameCodec.FLAG_QUERY_ACTIVE) != 0;
      boolean endOfStream = (flags & ProtocolFrameCodec.FLAG_END_OF_STREAM) != 0;
      int minimumMetadata = ProtocolResponseNullBitmap.bytes(columns)
          + columns * (Integer.BYTES + 1);
      int variableBytes = frame.payloadBytes() - FIXED_BYTES;
      return (frame.type() == ProtocolMessageType.BEGIN_QUERY
              || frame.type() == ProtocolMessageType.BEGIN_PREPARED_QUERY)
          && status.isOk()
          && !preparedQuery && columns > 0 && active != endOfStream
          && (!active || row)
          && (!active || (flags & ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE) == 0)
          && returned == (row ? 1 : 0) && (row || key == 0)
          && nullBytes == (row ? ProtocolResponseNullBitmap.bytes(columns) : 0)
          && reserved >= minimumMetadata && reserved <= variableBytes - nullBytes;
    }
    if (preparedQuery) return false;
    if (status.isOk() && (frame.type() == ProtocolMessageType.BEGIN_QUERY
        || frame.type() == ProtocolMessageType.BEGIN_PREPARED_QUERY)) return false;
    if (!ProtocolResponseNullBitmap.validSize(columns, nullBytes, reserved)) return false;
    boolean active = (flags & ProtocolFrameCodec.FLAG_QUERY_ACTIVE) != 0;
    boolean endOfStream = (flags & ProtocolFrameCodec.FLAG_END_OF_STREAM) != 0;
    if (endOfStream && frame.type() != ProtocolMessageType.FETCH || active && endOfStream) {
      return false;
    }
    if (frame.type() == ProtocolMessageType.FETCH && status.isOk()
        && (active == endOfStream
            || (flags & ProtocolFrameCodec.FLAG_ROW_AVAILABLE) == 0)) return false;
    return ((flags & ProtocolFrameCodec.FLAG_ROW_AVAILABLE) != 0) == (columns > 0);
  }

  static StatusCode statusFromStableCode(int code) {
    return switch (code) {
      case 0 -> StatusCode.OK; case 1000 -> StatusCode.RETRY; case 1001 -> StatusCode.FENCED;
      case 1002 -> StatusCode.CLOSED; case 2000 -> StatusCode.CANCELLED;
      case 3000 -> StatusCode.INVALID_EXTERNAL_INPUT; case 3001 -> StatusCode.CARDINALITY_VIOLATION;
      case 3002 -> StatusCode.NUMERIC_VALUE_OUT_OF_RANGE; case 3003 -> StatusCode.CHECK_VIOLATION;
      case 3004 -> StatusCode.UNIQUE_VIOLATION; case 3005 -> StatusCode.FOREIGN_KEY_VIOLATION;
      case 3006 -> StatusCode.DATATYPE_MISMATCH; case 3007 -> StatusCode.ACCESS_DENIED;
      case 3008 -> StatusCode.DIVISION_BY_ZERO; case 3009 -> StatusCode.INVALID_DATETIME_FORMAT;
      case 3010 -> StatusCode.DATETIME_FIELD_OVERFLOW; case 3011 -> StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
      case 3012 -> StatusCode.STRING_DATA_RIGHT_TRUNCATION; case 3013 -> StatusCode.FEATURE_NOT_SUPPORTED;
      case 3014 -> StatusCode.PARAMETER_COUNT_MISMATCH; case 4000 -> StatusCode.CONFLICT;
      case 4001 -> StatusCode.NOT_OWNER; case 4002 -> StatusCode.DEADLOCK;
      case 5000 -> StatusCode.RESOURCE_EXHAUSTED;
      case 5001 -> StatusCode.QUERY_TOO_COMPLEX; case 6000 -> StatusCode.TIMEOUT;
      case 7000 -> StatusCode.IO_FAILURE; case 8000 -> StatusCode.CORRUPTION;
      case 9000 -> StatusCode.INVARIANT_BROKEN; default -> null;
    };
  }
}
