package io.riverdb.format.btree;

import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlApproximateNumeric;
import java.nio.ByteBuffer;

/** Checks tuple components against the exact immutable descriptor shape. */
final class TupleKeyShapeValidation {
  private TupleKeyShapeValidation() { }

  static boolean matches(
      ByteBuffer source, int offset, int length, TupleShape shape) {
    if (shape == null || !TupleKeyStructureValidation.validate(source, offset, length)
        || TupleKeyCodec.arity(source, offset, length) != shape.partCount()) return false;
    int cursor = offset + TupleKeyCodec.headerBytes(source, offset, length);
    int valuesEnd = offset + TupleKeyCodec.userTupleBytes(source, offset, length);
    for (int part = 0; part < shape.partCount(); part++) {
      int descriptor = shape.descriptorAt(part);
      if (!matchesPart(source, cursor, descriptor)) return false;
      cursor = TupleKeyStructureValidation.partEnd(source, cursor, valuesEnd);
    }
    return cursor == valuesEnd;
  }

  private static boolean matchesPart(ByteBuffer source, int cursor, int descriptor) {
    int type = Byte.toUnsignedInt(source.get(cursor));
    int marker = Byte.toUnsignedInt(source.get(cursor + 1));
    if (type != SqlTypeDescriptor.typeId(descriptor)) return false;
    if (marker == TupleKeyCodec.NULL_VALUE) return true;
    if (type == SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      long high = TupleKeyCodec.getBigEndianLong(source, cursor + 2) ^ Long.MIN_VALUE;
      long low = TupleKeyCodec.getBigEndianLong(source, cursor + 2 + Long.BYTES);
      return ExactDecimal128.fits(
          high, low, SqlTypeDescriptor.parameterOne(descriptor));
    }
    if (type != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      long encoded = TupleKeyCodec.getBigEndianLong(source, cursor + 2);
      long value = SqlNumericTypeRules.isApproximate(descriptor)
          ? SqlApproximateNumeric.valueBits(descriptor, encoded)
          : encoded ^ Long.MIN_VALUE;
      return SqlValueDomain.validFixed(descriptor, value);
    }
    int scalars = 0;
    cursor += 2;
    while (TupleKeyCodec.getBigEndianInt(source, cursor) != 0) {
      cursor += Integer.BYTES;
      if (++scalars > SqlTypeDescriptor.parameterOne(descriptor)) return false;
    }
    return true;
  }

}
