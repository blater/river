package io.riverdb.protocol;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.ByteBuffer;

/** Canonical typed-cell width and emission for program results. */
final class ProtocolProgramResultValueCodec {
  private ProtocolProgramResultValueCodec() { }

  static int bytes(TransactionProgramResult result, int row, int column) {
    int descriptor = result.typeDescriptorAt(row, column);
    if (!SqlTypeDescriptor.isValid(descriptor)) return -1;
    if (result.isNull(row, column)) return 0;
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return ProtocolDecimal128.bytes(descriptor);
    }
    int characters = result.textLengthAt(row, column);
    if (characters < 0 || characters > SqlTypeDescriptor.parameterOne(descriptor)) return -1;
    int encoded = 0;
    for (int index = 0; index < characters; index++) {
      char value = result.textCharacterAt(row, column, index);
      if (value < 0x80) encoded++;
      else if (value < 0x800) encoded += 2;
      else if (Character.isHighSurrogate(value)) {
        if (++index >= characters || !Character.isLowSurrogate(
            result.textCharacterAt(row, column, index))) return -1;
        encoded += 4;
      } else if (Character.isLowSurrogate(value)) return -1;
      if (encoded > 0xffff) return -1;
    }
    return encoded;
  }

  static int write(
      ByteBuffer target, int offset, TransactionProgramResult result, int row, int column) {
    int descriptor = result.typeDescriptorAt(row, column);
    boolean isNull = result.isNull(row, column);
    int bytes = bytes(result, row, column);
    target.putInt(offset, descriptor);
    target.put(offset + 4, isNull ? (byte) 1 : 0);
    target.put(offset + 5, (byte) 0);
    target.putShort(offset + 6, (short) bytes);
    offset += ProtocolProgramResultEncoder.VALUE_HEADER_BYTES;
    if (isNull) return offset;
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      if (ProtocolDecimal128.isWide(descriptor)) {
        target.putLong(offset, result.highValueAt(row, column));
        offset += Long.BYTES;
      }
      target.putLong(offset, result.valueAt(row, column));
      return offset + Long.BYTES;
    }
    int characters = result.textLengthAt(row, column);
    for (int index = 0; index < characters; index++) {
      char value = result.textCharacterAt(row, column, index);
      if (value < 0x80) target.put(offset++, (byte) value);
      else if (value < 0x800) {
        target.put(offset++, (byte) (0xc0 | value >>> 6));
        target.put(offset++, (byte) (0x80 | value & 0x3f));
      } else if (Character.isHighSurrogate(value)) {
        int scalar = Character.toCodePoint(value,
            result.textCharacterAt(row, column, ++index));
        target.put(offset++, (byte) (0xf0 | scalar >>> 18));
        target.put(offset++, (byte) (0x80 | scalar >>> 12 & 0x3f));
        target.put(offset++, (byte) (0x80 | scalar >>> 6 & 0x3f));
        target.put(offset++, (byte) (0x80 | scalar & 0x3f));
      } else {
        target.put(offset++, (byte) (0xe0 | value >>> 12));
        target.put(offset++, (byte) (0x80 | value >>> 6 & 0x3f));
        target.put(offset++, (byte) (0x80 | value & 0x3f));
      }
    }
    return offset;
  }
}
