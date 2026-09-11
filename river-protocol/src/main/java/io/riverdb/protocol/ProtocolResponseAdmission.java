package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.ParameterSet;

/** Validates the fixed response fields before variable payload admission. */
final class ProtocolResponseAdmission {
  private static final int VALID_FLAGS = ProtocolFrameCodec.FLAG_ROW_AVAILABLE
      | ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE | ProtocolFrameCodec.FLAG_QUERY_ACTIVE
      | ProtocolFrameCodec.FLAG_COLUMN_METADATA | ProtocolFrameCodec.FLAG_PREPARED_QUERY
      | ProtocolFrameCodec.FLAG_END_OF_STREAM;

  private ProtocolResponseAdmission() { }

  static boolean validFixed(ProtocolFrame frame, StatusCode status, int flags, int rows,
      int columns, long commitSequence, long key, long returned, int nullBytes, int reserved) {
    if (status == null || (flags & ~VALID_FLAGS) != 0 || rows < 0 || columns < 0
        || columns > CommandResult.MAXIMUM_COLUMNS || commitSequence < 0 || returned < 0) {
      return false;
    }
    boolean metadata = (flags & ProtocolFrameCodec.FLAG_COLUMN_METADATA) != 0;
    boolean preparedQuery = (flags & ProtocolFrameCodec.FLAG_PREPARED_QUERY) != 0;
    if (frame.type() == ProtocolMessageType.PREPARE) {
      return validPrepare(status, flags, rows, columns, commitSequence, key, returned);
    }
    if (metadata) {
      return ProtocolQueryOpenResponseDecoder.validHeader(frame, status, flags, columns, key,
          returned, nullBytes, reserved, preparedQuery);
    }
    return validRows(frame, status, flags, columns, nullBytes, reserved, preparedQuery);
  }

  private static boolean validPrepare(
      StatusCode status, int flags, int rows, int columns, long commitSequence, long key,
      long returned) {
    return status.isOk()
        ? (flags == 0 || flags == ProtocolFrameCodec.FLAG_PREPARED_QUERY)
            && rows <= ParameterSet.MAXIMUM_PARAMETERS
            && columns == 0 && commitSequence == 0 && key > 0 && returned == 0
        : flags == 0 && rows == 0 && columns == 0 && commitSequence == 0
            && key == 0 && returned == 0;
  }


  private static boolean validRows(
      ProtocolFrame frame, StatusCode status, int flags, int columns,
      int nullBytes, int reserved, boolean preparedQuery) {
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
}
