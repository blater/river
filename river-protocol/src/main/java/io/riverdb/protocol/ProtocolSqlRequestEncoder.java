package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import java.nio.ByteBuffer;

/** Encodes one v3 SQL text plus typed-parameter request without intermediate storage. */
final class ProtocolSqlRequestEncoder {
  private static final int REQUEST_HEADER_BYTES = Integer.BYTES + Short.BYTES * 2;
  private static final int ENTRY_HEADER_BYTES = Integer.BYTES + Byte.BYTES * 2 + Short.BYTES;
  private static final int NULL_FLAG = 1;

  StatusCode encode(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      String sql,
      ParameterSet parameters) {
    if (target == null || !sqlType(type) || requestId <= 0
        || sql == null || sql.isEmpty()) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int sqlBytes = utf8Length(sql);
    if (sqlBytes < 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int parameterCount = parameters == null ? 0 : parameters.count();
    int payloadBytes = REQUEST_HEADER_BYTES + sqlBytes;
    for (int index = 0; index < parameterCount; index++) {
      int valueBytes = valueBytes(parameters, index);
      if (valueBytes < 0
          || payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
              - ENTRY_HEADER_BYTES - valueBytes) {
        ProtocolFrameWire.empty(target);
        return valueBytes < 0
            ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.RESOURCE_EXHAUSTED;
      }
      payloadBytes += ENTRY_HEADER_BYTES + valueBytes;
    }
    if (payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
      ProtocolFrameWire.empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = ProtocolFrameWire.begin(
        target, type, requestId, payloadBytes, 0);
    if (!status.isOk()) {
      return status;
    }
    int output = ProtocolFrameCodec.HEADER_BYTES;
    target.putInt(output, sqlBytes);
    target.putShort(output + Integer.BYTES, (short) parameterCount);
    target.putShort(output + Integer.BYTES + Short.BYTES, (short) 0);
    output += REQUEST_HEADER_BYTES;
    output = writeUtf8(target, output, sql);
    for (int index = 0; index < parameterCount; index++) {
      output = writeParameter(target, output, parameters, index);
    }
    target.position(0);
    target.limit(ProtocolFrameCodec.HEADER_BYTES + payloadBytes);
    return StatusCode.OK;
  }

  private static int valueBytes(ParameterSet parameters, int index) {
    int descriptor = parameters.typeDescriptorAt(index);
    if (parameters.isNull(index)) {
      return descriptor == 0 || SqlTypeDescriptor.isValid(descriptor) ? 0 : -1;
    }
    if (!SqlTypeDescriptor.isValid(descriptor)) {
      return -1;
    }
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? parameters.textLengthAt(index) : Long.BYTES;
  }

  private static int writeParameter(
      ByteBuffer target, int output, ParameterSet parameters, int index) {
    int descriptor = parameters.typeDescriptorAt(index);
    boolean nullValue = parameters.isNull(index);
    int valueBytes = valueBytes(parameters, index);
    target.putInt(output, descriptor);
    target.put(output + Integer.BYTES, nullValue ? (byte) NULL_FLAG : 0);
    target.put(output + Integer.BYTES + Byte.BYTES, (byte) 0);
    target.putShort(
        output + Integer.BYTES + Byte.BYTES * 2, (short) valueBytes);
    output += ENTRY_HEADER_BYTES;
    if (nullValue) {
      return output;
    }
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      target.putLong(output, parameters.valueAt(index));
      return output + Long.BYTES;
    }
    for (int byteIndex = 0; byteIndex < valueBytes; byteIndex++) {
      target.put(output++, parameters.textByteAt(index, byteIndex));
    }
    return output;
  }

  private static int utf8Length(String text) {
    int bytes = 0;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character < 0x80) {
        bytes++;
      } else if (character < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(character)) {
        if (++index >= text.length()
            || !Character.isLowSurrogate(text.charAt(index))) {
          return -1;
        }
        bytes += 4;
      } else if (Character.isLowSurrogate(character)) {
        return -1;
      } else {
        bytes += 3;
      }
      if (bytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
        return bytes;
      }
    }
    return bytes;
  }

  private static int writeUtf8(ByteBuffer target, int output, String text) {
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character < 0x80) {
        target.put(output++, (byte) character);
      } else if (character < 0x800) {
        target.put(output++, (byte) (0xc0 | character >>> 6));
        target.put(output++, (byte) (0x80 | character & 0x3f));
      } else if (Character.isHighSurrogate(character)) {
        int scalar = Character.toCodePoint(character, text.charAt(++index));
        target.put(output++, (byte) (0xf0 | scalar >>> 18));
        target.put(output++, (byte) (0x80 | scalar >>> 12 & 0x3f));
        target.put(output++, (byte) (0x80 | scalar >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | scalar & 0x3f));
      } else {
        target.put(output++, (byte) (0xe0 | character >>> 12));
        target.put(output++, (byte) (0x80 | character >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | character & 0x3f));
      }
    }
    return output;
  }

  private static boolean sqlType(ProtocolMessageType type) {
    return type == ProtocolMessageType.EXECUTE
        || type == ProtocolMessageType.BEGIN_QUERY;
  }
}
