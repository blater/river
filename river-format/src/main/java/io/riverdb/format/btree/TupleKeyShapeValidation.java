package io.riverdb.format.btree;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.nio.ByteBuffer;

/** Checks tuple-key component descriptors against a declared shape. */
final class TupleKeyShapeValidation {
  private TupleKeyShapeValidation() { }

  static boolean matches(ByteBuffer source, int offset, int length, int arity,
      int first, int second, int third, int fourth) {
    if (!TupleKeyStructureValidation.validate(source, offset, length)
        || Byte.toUnsignedInt(source.get(offset + 1)) != arity
        || !TupleKeyCodec.validShape(arity, first, second, third, fourth)) return false;
    int cursor = offset + TupleKeyCodec.HEADER_BYTES, valuesEnd = offset + length - Long.BYTES;
    for (int column = 0; column < arity; column++) {
      int type = Byte.toUnsignedInt(source.get(cursor++));
      int descriptor = descriptorAt(column, first, second, third, fourth);
      if (type != SqlTypeDescriptor.typeId(descriptor)) return false;
      int marker = Byte.toUnsignedInt(source.get(cursor++));
      if (marker == TupleKeyCodec.NULL_VALUE) continue;
      if (type == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        int scalars = 0;
        while (getInt(source, cursor) != 0) { cursor += Integer.BYTES; scalars++; }
        cursor += Integer.BYTES;
        if (scalars > SqlTypeDescriptor.parameterOne(descriptor)) return false;
      } else {
        long value = getLong(source, cursor) ^ Long.MIN_VALUE;
        if (!SqlValueDomain.validFixed(descriptor, value)) return false;
        cursor += Long.BYTES;
      }
    }
    return cursor == valuesEnd;
  }
  private static int descriptorAt(int index, int first, int second, int third, int fourth) { return switch (index) { case 0 -> first; case 1 -> second; case 2 -> third; case 3 -> fourth; default -> 0; }; }
  private static int getInt(ByteBuffer source, int offset) { return Byte.toUnsignedInt(source.get(offset)) << 24 | Byte.toUnsignedInt(source.get(offset + 1)) << 16 | Byte.toUnsignedInt(source.get(offset + 2)) << 8 | Byte.toUnsignedInt(source.get(offset + 3)); }
  private static long getLong(ByteBuffer source, int offset) { return (long) getInt(source, offset) << 32 | Integer.toUnsignedLong(getInt(source, offset + Integer.BYTES)); }
}
