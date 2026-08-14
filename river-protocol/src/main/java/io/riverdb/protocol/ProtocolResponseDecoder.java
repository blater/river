package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import java.nio.ByteBuffer;

/** Decodes a validated response payload into a reusable carrier. */
final class ProtocolResponseDecoder {
  private static final int FIXED_BYTES = 64;
  private static final int VALID_FLAGS = ProtocolFrameCodec.FLAG_ROW_AVAILABLE
      | ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE
      | ProtocolFrameCodec.FLAG_QUERY_ACTIVE
      | ProtocolFrameCodec.FLAG_COLUMN_METADATA;

  StatusCode decode(
      ByteBuffer source, ProtocolFrame frame, ProtocolResponse result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = ProtocolFrameWire.decode(
        source, frame, ProtocolFrameWire.ROLE_RESPONSE);
    if (!status.isOk()) {
      return status;
    }
    status = decodePayload(frame, result);
    if (!status.isOk()) {
      frame.reset();
      result.reset();
    }
    return status;
  }

  private StatusCode decodePayload(
      ProtocolFrame frame, ProtocolResponse result) {
    int offset = frame.payloadOffset();
    ByteBuffer bytes = frame.source();
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
    if (!validFixed(
        frame, status, flags, rows, columns, commitSequence, returned, nullMask)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.complete(
        status,
        flags,
        rows,
        columns,
        commitSequence,
        key,
        returned,
        challengeHigh,
        challengeLow,
        nullMask);
    int end = offset + frame.payloadBytes();
    int valueOffset = decodeTypes(bytes, offset + FIXED_BYTES, end, columns, result);
    if (valueOffset < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return (flags & ProtocolFrameCodec.FLAG_COLUMN_METADATA) != 0
        ? decodeMetadata(bytes, valueOffset, end, columns, result)
        : decodeValues(bytes, valueOffset, end, columns, nullMask, result);
  }

  private static boolean validFixed(
      ProtocolFrame frame,
      StatusCode status,
      int flags,
      int rows,
      int columns,
      long commitSequence,
      long returned,
      long nullMask) {
    if (status == null || (flags & ~VALID_FLAGS) != 0 || rows < 0
        || columns < 0 || columns > CommandResult.MAXIMUM_COLUMNS
        || commitSequence < 0 || returned < 0
        || (nullMask & ~((1L << columns) - 1)) != 0) {
      return false;
    }
    boolean metadata = (flags & ProtocolFrameCodec.FLAG_COLUMN_METADATA) != 0;
    if (metadata) {
      return frame.type() == ProtocolMessageType.BEGIN_QUERY && status.isOk()
          && flags == (ProtocolFrameCodec.FLAG_QUERY_ACTIVE
              | ProtocolFrameCodec.FLAG_COLUMN_METADATA)
          && columns > 0 && nullMask == 0;
    }
    boolean rowAvailable =
        (flags & ProtocolFrameCodec.FLAG_ROW_AVAILABLE) != 0;
    return rowAvailable == (columns > 0);
  }

  private static int decodeTypes(
      ByteBuffer bytes,
      int offset,
      int end,
      int columns,
      ProtocolResponse result) {
    if (offset > end - columns * Integer.BYTES) {
      return -1;
    }
    for (int index = 0; index < columns; index++) {
      int descriptor = bytes.getInt(offset);
      if (!SqlTypeDescriptor.isValid(descriptor)) {
        return -1;
      }
      result.typeDescriptorAt(index, descriptor);
      offset += Integer.BYTES;
    }
    return offset;
  }

  private static StatusCode decodeMetadata(
      ByteBuffer bytes,
      int offset,
      int end,
      int columns,
      ProtocolResponse result) {
    for (int index = 0; index < columns; index++) {
      if (offset >= end) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int length = bytes.get(offset++) & 0xff;
      if (!validColumnName(bytes, offset, length, end)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.columnNameAt(index, bytes, offset, length);
      offset += length;
    }
    return offset == end ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static StatusCode decodeValues(
      ByteBuffer bytes,
      int offset,
      int end,
      int columns,
      long nullMask,
      ProtocolResponse result) {
    for (int index = 0; index < columns; index++) {
      int next = result.isVarchar(index)
          ? decodeText(bytes, offset, end, index, nullMask, result)
          : decodeLong(bytes, offset, end, index, result);
      if (next < 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      offset = next;
    }
    return offset == end ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static int decodeText(
      ByteBuffer bytes,
      int offset,
      int end,
      int index,
      long nullMask,
      ProtocolResponse result) {
    if (offset > end - Short.BYTES) {
      return -1;
    }
    int length = Short.toUnsignedInt(bytes.getShort(offset));
    offset += Short.BYTES;
    int maximumScalars = SqlTypeDescriptor.parameterOne(
        result.typeDescriptorAt(index));
    if (length > Utf8Text.MAXIMUM_BYTES
        || (nullMask & 1L << index) != 0 && length != 0
        || offset > end - length
        || Utf8Text.validate(bytes, offset, length, maximumScalars) < 0
        || !result.textAt(index, bytes, offset, length)) {
      return -1;
    }
    return offset + length;
  }

  private static int decodeLong(
      ByteBuffer bytes,
      int offset,
      int end,
      int index,
      ProtocolResponse result) {
    if (offset > end - Long.BYTES) {
      return -1;
    }
    result.valueAt(index, bytes.getLong(offset));
    return offset + Long.BYTES;
  }

  private static boolean validColumnName(
      ByteBuffer source, int offset, int length, int end) {
    if (length <= 0 || length > ProtocolFrameCodec.MAXIMUM_COLUMN_NAME_BYTES
        || offset > end - length || !identifierStart(source.get(offset))) {
      return false;
    }
    for (int index = 1; index < length; index++) {
      if (!identifierPart(source.get(offset + index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean identifierStart(byte value) {
    return value >= 'a' && value <= 'z'
        || value >= 'A' && value <= 'Z' || value == '_';
  }

  private static boolean identifierPart(byte value) {
    return identifierStart(value) || value >= '0' && value <= '9';
  }

  private static StatusCode statusFromStableCode(int code) {
    return switch (code) {
      case 0 -> StatusCode.OK;
      case 1000 -> StatusCode.RETRY;
      case 1001 -> StatusCode.FENCED;
      case 1002 -> StatusCode.CLOSED;
      case 2000 -> StatusCode.CANCELLED;
      case 3000 -> StatusCode.INVALID_EXTERNAL_INPUT;
      case 3001 -> StatusCode.CARDINALITY_VIOLATION;
      case 3002 -> StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      case 3003 -> StatusCode.CHECK_VIOLATION;
      case 3004 -> StatusCode.UNIQUE_VIOLATION;
      case 3005 -> StatusCode.FOREIGN_KEY_VIOLATION;
      case 3006 -> StatusCode.DATATYPE_MISMATCH;
      case 3007 -> StatusCode.ACCESS_DENIED;
      case 3008 -> StatusCode.DIVISION_BY_ZERO;
      case 4000 -> StatusCode.CONFLICT;
      case 4001 -> StatusCode.NOT_OWNER;
      case 5000 -> StatusCode.RESOURCE_EXHAUSTED;
      case 5001 -> StatusCode.QUERY_TOO_COMPLEX;
      case 6000 -> StatusCode.TIMEOUT;
      case 7000 -> StatusCode.IO_FAILURE;
      case 8000 -> StatusCode.CORRUPTION;
      case 9000 -> StatusCode.INVARIANT_BROKEN;
      default -> null;
    };
  }
}
