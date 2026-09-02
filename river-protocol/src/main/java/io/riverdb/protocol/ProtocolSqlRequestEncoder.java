package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.TransactionProgramArguments;
import java.nio.ByteBuffer;

/** Encodes one v4 SQL text plus typed-parameter request without intermediate storage. */
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
    if (parameterCount < 0 || parameterCount > ParameterSet.MAXIMUM_PARAMETERS
        || type == ProtocolMessageType.PREPARE && parameterCount != 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    long payload = (long) REQUEST_HEADER_BYTES + sqlBytes;
    for (int index = 0; index < parameterCount; index++) {
      int valueBytes = valueBytes(parameters, index);
      if (valueBytes < 0) {
        ProtocolFrameWire.empty(target);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      payload += ENTRY_HEADER_BYTES + valueBytes;
    }
    int payloadBytes = (int) payload;
    StatusCode prepared = ProtocolRequestSegmenter.prepare(target, payloadBytes);
    if (!prepared.isOk()) return prepared;
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
    return ProtocolRequestSegmenter.finish(target, type, requestId, payloadBytes);
  }

  static int valueBytes(ParameterSet parameters, int index) {
    int descriptor = parameters.typeDescriptorAt(index);
    if (parameters.isNull(index)) {
      return descriptor == 0 || SqlTypeDescriptor.isValid(descriptor) ? 0 : -1;
    }
    if (!SqlTypeDescriptor.isValid(descriptor)) {
      return -1;
    }
    int bytes = SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? parameters.textLengthAt(index) : ProtocolDecimal128.bytes(descriptor);
    /* The v4 entry carries a 16-bit byte length; reject before narrowing. */
    return bytes >= 0 && bytes <= 0xffff ? bytes : -1;
  }

  static int writeParameter(
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
      if (ProtocolDecimal128.isWide(descriptor)) {
        target.putLong(output, parameters.decimalUnscaledHighAt(index));
        output += Long.BYTES;
      }
      target.putLong(output, parameters.valueAt(index));
      return output + Long.BYTES;
    }
    for (int byteIndex = 0; byteIndex < valueBytes; byteIndex++) {
      target.put(output++, parameters.textByteAt(index, byteIndex));
    }
    return output;
  }

  static int valueBytes(TransactionProgramArguments parameters, int index) {
    if (!parameters.isSet(index)) return -1;
    int descriptor = parameters.typeDescriptorAt(index);
    if (parameters.isNull(index)) {
      return descriptor == 0 || SqlTypeDescriptor.isValid(descriptor) ? 0 : -1;
    }
    if (!SqlTypeDescriptor.isValid(descriptor)) return -1;
    int bytes = SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? encodedTextBytes(parameters, index) : ProtocolDecimal128.bytes(descriptor);
    return bytes >= 0 && bytes <= 0xffff ? bytes : -1;
  }

  static int writeParameter(
      ByteBuffer target, int output, TransactionProgramArguments parameters, int index) {
    int descriptor = parameters.typeDescriptorAt(index);
    boolean nullValue = parameters.isNull(index);
    int valueBytes = valueBytes(parameters, index);
    target.putInt(output, descriptor);
    target.put(output + Integer.BYTES, nullValue ? (byte) NULL_FLAG : 0);
    target.put(output + Integer.BYTES + Byte.BYTES, (byte) 0);
    target.putShort(output + Integer.BYTES + Byte.BYTES * 2, (short) valueBytes);
    output += ENTRY_HEADER_BYTES;
    if (nullValue) return output;
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      if (ProtocolDecimal128.isWide(descriptor)) {
        target.putLong(output, parameters.highValueAt(index));
        output += Long.BYTES;
      }
      target.putLong(output, parameters.valueAt(index));
      return output + Long.BYTES;
    }
    return writeText(target, output, parameters, index);
  }

  private static int encodedTextBytes(TransactionProgramArguments parameters, int index) {
    int characters = parameters.textLengthAt(index);
    if (characters < 0) return -1;
    int bytes = 0;
    for (int character = 0; character < characters; character++) {
      char value = parameters.textCharacterAt(index, character);
      if (value < 0x80) bytes++;
      else if (value < 0x800) bytes += 2;
      else if (Character.isHighSurrogate(value)) {
        if (++character >= characters || !Character.isLowSurrogate(
            parameters.textCharacterAt(index, character))) return -1;
        bytes += 4;
      } else if (Character.isLowSurrogate(value)) return -1;
      else bytes += 3;
    }
    return bytes;
  }

  private static int writeText(
      ByteBuffer target, int output, TransactionProgramArguments parameters, int index) {
    int characters = parameters.textLengthAt(index);
    for (int character = 0; character < characters; character++) {
      char value = parameters.textCharacterAt(index, character);
      if (value < 0x80) {
        target.put(output++, (byte) value);
      } else if (value < 0x800) {
        target.put(output++, (byte) (0xc0 | value >>> 6));
        target.put(output++, (byte) (0x80 | value & 0x3f));
      } else if (Character.isHighSurrogate(value)) {
        int scalar = Character.toCodePoint(value,
            parameters.textCharacterAt(index, ++character));
        target.put(output++, (byte) (0xf0 | scalar >>> 18));
        target.put(output++, (byte) (0x80 | scalar >>> 12 & 0x3f));
        target.put(output++, (byte) (0x80 | scalar >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | scalar & 0x3f));
      } else {
        target.put(output++, (byte) (0xe0 | value >>> 12));
        target.put(output++, (byte) (0x80 | value >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | value & 0x3f));
      }
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
      if (bytes > io.riverdb.base.sql.SqlShapeLimits.MAX_SQL_TEXT_BYTES) {
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
        || type == ProtocolMessageType.BEGIN_QUERY
        || type == ProtocolMessageType.PREPARE;
  }
}
