package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;

/** Validates fixed response fields and delegates variable values to the value decoder. */
final class ProtocolResponsePayloadDecoder {
  private static final int FIXED_BYTES = 64;
  private static final int VALID_FLAGS = ProtocolFrameCodec.FLAG_ROW_AVAILABLE
      | ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE | ProtocolFrameCodec.FLAG_QUERY_ACTIVE
      | ProtocolFrameCodec.FLAG_COLUMN_METADATA;

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
    long nullMask = bytes.getLong(offset + 56);
    if (!validFixed(frame, status, flags, rows, columns, commitSequence, returned, nullMask)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.complete(status, flags, rows, columns, commitSequence, key, returned,
        challengeHigh, challengeLow, nullMask);
    int end = offset + frame.payloadBytes();
    int valueOffset = ProtocolResponseValueDecoder.types(bytes, offset + FIXED_BYTES, end,
        columns, result);
    if (valueOffset < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    return (flags & ProtocolFrameCodec.FLAG_COLUMN_METADATA) != 0
        ? ProtocolResponseValueDecoder.metadata(bytes, valueOffset, end, columns, result)
        : ProtocolResponseValueDecoder.values(bytes, valueOffset, end, columns, nullMask, result);
  }

  private static boolean validFixed(ProtocolFrame frame, StatusCode status, int flags, int rows,
      int columns, long commitSequence, long returned, long nullMask) {
    if (status == null || (flags & ~VALID_FLAGS) != 0 || rows < 0 || columns < 0
        || columns > CommandResult.MAXIMUM_COLUMNS || commitSequence < 0 || returned < 0
        || (nullMask & ~((1L << columns) - 1)) != 0) return false;
    boolean metadata = (flags & ProtocolFrameCodec.FLAG_COLUMN_METADATA) != 0;
    if (metadata) {
      return frame.type() == ProtocolMessageType.BEGIN_QUERY && status.isOk()
          && flags == (ProtocolFrameCodec.FLAG_QUERY_ACTIVE | ProtocolFrameCodec.FLAG_COLUMN_METADATA)
          && columns > 0;
    }
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
      case 4001 -> StatusCode.NOT_OWNER; case 5000 -> StatusCode.RESOURCE_EXHAUSTED;
      case 5001 -> StatusCode.QUERY_TOO_COMPLEX; case 6000 -> StatusCode.TIMEOUT;
      case 7000 -> StatusCode.IO_FAILURE; case 8000 -> StatusCode.CORRUPTION;
      case 9000 -> StatusCode.INVARIANT_BROKEN; default -> null;
    };
  }
}
