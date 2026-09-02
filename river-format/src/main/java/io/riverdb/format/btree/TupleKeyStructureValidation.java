package io.riverdb.format.btree;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Validates the self-describing tuple stream before descriptor-specific checks. */
final class TupleKeyStructureValidation {
  private TupleKeyStructureValidation() { }

  static boolean validate(ByteBuffer source, int offset, int length) {
    if (source == null || offset < 0 || length < TupleKeyCodec.MINIMUM_HEADER_BYTES + 2
        || length > TupleKeyCodec.MAX_GENERIC_TUPLE_BYTES
        || source.limit() - offset < length
        || Byte.toUnsignedInt(source.get(offset)) != TupleKeyCodec.VERSION) return false;
    int flags = Byte.toUnsignedInt(source.get(offset + 1));
    int arity = TupleKeyCodec.arity(source, offset, length);
    int headerBytes = TupleKeyCodec.headerBytes(source, offset, length);
    if (flags != 0 && flags != TupleKeyCodec.FLAG_PHYSICAL
        || arity == 0 || !canonicalArity(source, offset, arity, headerBytes)) return false;
    int suffix = flags == TupleKeyCodec.FLAG_PHYSICAL ? Long.BYTES : 0;
    int valuesEnd = offset + length - suffix;
    int cursor = offset + headerBytes;
    for (int part = 0; part < arity; part++) {
      int end = partEnd(source, cursor, valuesEnd);
      if (end < 0) return false;
      int type = Byte.toUnsignedInt(source.get(cursor));
      int marker = Byte.toUnsignedInt(source.get(cursor + 1));
      if (marker == TupleKeyCodec.PRESENT_VALUE
          && !TupleKeyValueValidation.valid(source, cursor + 2, type)) return false;
      cursor = end;
    }
    return cursor == valuesEnd
        && (suffix == 0 || TupleKeyCodec.getBigEndianLong(source, valuesEnd) > 0);
  }

  static int partEnd(ByteBuffer source, int cursor, int valuesEnd) {
    if (source == null || cursor < 0 || valuesEnd - cursor < 2) return -1;
    int type = Byte.toUnsignedInt(source.get(cursor));
    int marker = Byte.toUnsignedInt(source.get(cursor + 1));
    if (!validType(type)
        || marker != TupleKeyCodec.NULL_VALUE && marker != TupleKeyCodec.PRESENT_VALUE) return -1;
    return marker == TupleKeyCodec.NULL_VALUE
        ? cursor + 2 : valueEnd(source, cursor + 2, valuesEnd, type);
  }

  static int valueEnd(ByteBuffer source, int cursor, int valuesEnd, int type) {
    if (type != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      int width = type == SqlTypeDescriptor.TYPE_ID_DECIMAL
          ? Long.BYTES * 2 : Long.BYTES;
      return valuesEnd - cursor >= width ? cursor + width : -1;
    }
    int scalars = 0;
    while (valuesEnd - cursor >= Integer.BYTES) {
      int encoded = TupleKeyCodec.getBigEndianInt(source, cursor);
      cursor += Integer.BYTES;
      if (encoded == 0) return cursor;
      int scalar = encoded - 1;
      if (!Character.isValidCodePoint(scalar)
          || scalar >= Character.MIN_SURROGATE && scalar <= Character.MAX_SURROGATE
          || ++scalars > SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS) return -1;
    }
    return -1;
  }

  private static boolean canonicalArity(
      ByteBuffer source, int offset, int arity, int headerBytes) {
    return headerBytes == TupleKeyCodec.headerBytes(arity)
        && (Byte.toUnsignedInt(source.get(offset + headerBytes - 1)) & 0x80) == 0;
  }

  private static boolean validType(int type) {
    return type >= SqlTypeDescriptor.TYPE_ID_BIGINT
        && type <= SqlTypeDescriptor.TYPE_ID_DOUBLE;
  }
}
