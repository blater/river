package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.ByteBuffer;

/** Validates and appends one canonical typed result cell. */
final class ProtocolProgramResultValueDecoder {
  private ProtocolProgramResultValueDecoder() { }

  static int validate(ByteBuffer source, int offset, int end) {
    if (offset > end - ProtocolProgramResultEncoder.VALUE_HEADER_BYTES) return -1;
    int descriptor = source.getInt(offset);
    int flags = Byte.toUnsignedInt(source.get(offset + 4));
    int reserved = Byte.toUnsignedInt(source.get(offset + 5));
    int bytes = ProtocolValueHeader.length(source, offset);
    int value = offset + ProtocolProgramResultEncoder.VALUE_HEADER_BYTES;
    if ((flags & ~1) != 0 || reserved != 0 || !SqlTypeDescriptor.isValid(descriptor)
        || bytes < 0 || value > end - bytes) return -1;
    if (flags == 1) return bytes == 0 ? value : -1;
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return Utf8Text.validate(source, value, bytes,
          SqlTypeDescriptor.parameterOne(descriptor)) < 0 ? -1 : value + bytes;
    }
    if (bytes != ProtocolDecimal128.bytes(descriptor)) return -1;
    long high = ProtocolDecimal128.isWide(descriptor) ? source.getLong(value) : 0;
    long low = source.getLong(value + (ProtocolDecimal128.isWide(descriptor) ? Long.BYTES : 0));
    return ProtocolDecimal128.isWide(descriptor)
        ? SqlValueDomain.validDecimal128(descriptor, high, low) ? value + bytes : -1
        : high == 0 && SqlValueDomain.validFixed(descriptor, low) ? value + bytes : -1;
  }

  static StatusCode append(
      ByteBuffer source, int offset, TransactionProgramResult result,
      ProtocolProgramTextDecoder text) {
    int descriptor = source.getInt(offset);
    int flags = Byte.toUnsignedInt(source.get(offset + 4));
    int bytes = ProtocolValueHeader.length(source, offset);
    int value = offset + ProtocolProgramResultEncoder.VALUE_HEADER_BYTES;
    if (flags == 1) return result.appendNull(descriptor);
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      StatusCode status = text.decode(source, value, bytes);
      return status.isOk() ? result.appendText(descriptor, text.view()) : status;
    }
    long high = ProtocolDecimal128.isWide(descriptor) ? source.getLong(value) : 0;
    long low = source.getLong(value + (ProtocolDecimal128.isWide(descriptor) ? Long.BYTES : 0));
    return ProtocolDecimal128.isWide(descriptor)
        ? result.appendDecimal128(descriptor, high, low)
        : result.appendFixed(descriptor, high, low);
  }

  static int next(ByteBuffer source, int offset) {
    return offset + ProtocolProgramResultEncoder.VALUE_HEADER_BYTES
        + ProtocolValueHeader.length(source, offset);
  }
}
