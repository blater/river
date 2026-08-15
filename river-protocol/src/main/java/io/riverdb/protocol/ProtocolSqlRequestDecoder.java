package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import java.nio.ByteBuffer;

/** Session-owned strict decoder for one v3 SQL request payload. */
public final class ProtocolSqlRequestDecoder {
  private static final int REQUEST_HEADER_BYTES = Integer.BYTES + Short.BYTES * 2;
  private static final int ENTRY_HEADER_BYTES = Integer.BYTES + Byte.BYTES * 2 + Short.BYTES;
  private static final int NULL_FLAG = 1;

  private final ProtocolUtf8Decoder text =
      new ProtocolUtf8Decoder(ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES);
  private final ParameterSet parameters = new ParameterSet(
      ParameterSet.MAXIMUM_PARAMETERS, ParameterSet.MAXIMUM_TEXT_BYTES);
  private String sql;

  public StatusCode decode(ProtocolFrame frame) {
    reset();
    if (frame == null || frame.isResponse() || !sqlType(frame.type())
        || frame.payloadBytes() < REQUEST_HEADER_BYTES) {
      return invalid(frame);
    }
    ByteBuffer source = frame.source();
    int input = frame.payloadOffset();
    int end = input + frame.payloadBytes();
    int sqlBytes = source.getInt(input);
    int parameterCount = Short.toUnsignedInt(source.getShort(input + Integer.BYTES));
    int reserved = Short.toUnsignedInt(
        source.getShort(input + Integer.BYTES + Short.BYTES));
    input += REQUEST_HEADER_BYTES;
    if (sqlBytes <= 0 || reserved != 0
        || parameterCount > ParameterSet.MAXIMUM_PARAMETERS
        || input > end - sqlBytes) {
      return invalid(frame);
    }
    StatusCode status = text.decode(frame, input - frame.payloadOffset(), sqlBytes);
    if (!status.isOk()) {
      return invalid(frame);
    }
    sql = text.text();
    input += sqlBytes;
    for (int index = 0; index < parameterCount; index++) {
      input = decodeParameter(source, input, end);
      if (input < 0) {
        return invalid(frame);
      }
    }
    if (input != end) {
      return invalid(frame);
    }
    StatusCode erased = erase(frame);
    if (!erased.isOk()) {
      reset();
    }
    return erased;
  }

  public String sql() {
    return sql;
  }

  public ParameterSet parameters() {
    return parameters;
  }

  /** Releases decoded user values after synchronous engine admission. */
  public void reset() {
    sql = null;
    text.reset();
    parameters.reset();
  }

  private int decodeParameter(ByteBuffer source, int input, int end) {
    if (input > end - ENTRY_HEADER_BYTES) {
      return -1;
    }
    int descriptor = source.getInt(input);
    int flags = Byte.toUnsignedInt(source.get(input + Integer.BYTES));
    int reserved = Byte.toUnsignedInt(
        source.get(input + Integer.BYTES + Byte.BYTES));
    int valueBytes = Short.toUnsignedInt(
        source.getShort(input + Integer.BYTES + Byte.BYTES * 2));
    input += ENTRY_HEADER_BYTES;
    if ((flags & ~NULL_FLAG) != 0 || reserved != 0 || input > end - valueBytes) {
      return -1;
    }
    boolean nullValue = (flags & NULL_FLAG) != 0;
    StatusCode status;
    if (nullValue) {
      status = valueBytes == 0
          ? parameters.appendNull(descriptor) : StatusCode.INVALID_EXTERNAL_INPUT;
    } else if (!SqlTypeDescriptor.isValid(descriptor)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    } else if (SqlTypeDescriptor.typeId(descriptor)
        == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      status = parameters.appendUtf8(descriptor, source, input, valueBytes);
    } else {
      status = valueBytes == Long.BYTES
          ? parameters.appendFixed(descriptor, source.getLong(input))
          : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status.isOk() ? input + valueBytes : -1;
  }

  private StatusCode invalid(ProtocolFrame frame) {
    erase(frame);
    reset();
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static StatusCode erase(ProtocolFrame frame) {
    return frame == null
        ? StatusCode.INVALID_EXTERNAL_INPUT : frame.erasePayload();
  }

  private static boolean sqlType(ProtocolMessageType type) {
    return type == ProtocolMessageType.EXECUTE
        || type == ProtocolMessageType.BEGIN_QUERY;
  }
}
