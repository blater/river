package io.riverdb.format.btree;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.nio.ByteBuffer;

/** Validates the self-describing physical tuple-key stream. */
final class TupleKeyStructureValidation {
  private TupleKeyStructureValidation() { }

  static boolean validate(ByteBuffer source, int offset, int length) {
    if (source == null || offset < 0 || length < TupleKeyCodec.HEADER_BYTES + 2 + Long.BYTES
        || length > TupleKeyCodec.MAXIMUM_KEY_BYTES || source.limit() - offset < length
        || Byte.toUnsignedInt(source.get(offset)) != TupleKeyCodec.VERSION) return false;
    int arity = Byte.toUnsignedInt(source.get(offset + 1));
    if (arity <= 0 || arity > TupleKeyCodec.MAXIMUM_ARITY || source.get(offset + 2) != 0 || source.get(offset + 3) != 0) return false;
    int cursor = offset + TupleKeyCodec.HEADER_BYTES, valuesEnd = offset + length - Long.BYTES;
    for (int column = 0; column < arity; column++) {
      if (valuesEnd - cursor < 2) return false;
      int type = Byte.toUnsignedInt(source.get(cursor++)), marker = Byte.toUnsignedInt(source.get(cursor++));
      if (!validType(type) || marker != TupleKeyCodec.NULL_VALUE && marker != TupleKeyCodec.PRESENT_VALUE) return false;
      if (marker == TupleKeyCodec.NULL_VALUE) continue;
      if (type == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        boolean terminated = false; int scalars = 0;
        while (valuesEnd - cursor >= Integer.BYTES) {
          int encoded = getInt(source, cursor); cursor += Integer.BYTES;
          if (encoded == 0) { terminated = true; break; }
          int scalar = encoded - 1;
          if (!Character.isValidCodePoint(scalar) || scalar >= Character.MIN_SURROGATE && scalar <= Character.MAX_SURROGATE) return false;
          if (++scalars > SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS) return false;
        }
        if (!terminated) return false;
      } else {
        if (valuesEnd - cursor < Long.BYTES) return false;
        long value = getLong(source, cursor) ^ Long.MIN_VALUE;
        if (!SqlValueDomain.validFixed(globalDescriptor(type), value)) return false;
        cursor += Long.BYTES;
      }
    }
    return cursor == valuesEnd && getLong(source, valuesEnd) > 0;
  }

  private static boolean validType(int type) { return type >= SqlTypeDescriptor.TYPE_ID_BIGINT && type <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE; }
  private static int globalDescriptor(int type) {
    return switch (type) {
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> SqlTypeDescriptor.BIGINT;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> SqlTypeDescriptor.BOOLEAN;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 0);
      case SqlTypeDescriptor.TYPE_ID_DATE -> SqlTypeDescriptor.DATE;
      case SqlTypeDescriptor.TYPE_ID_TIME -> SqlTypeDescriptor.time(SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> SqlTypeDescriptor.timestamp(SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE -> SqlTypeDescriptor.timestampWithTimeZone(SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION);
      default -> 0;
    };
  }
  private static int getInt(ByteBuffer source, int offset) { return Byte.toUnsignedInt(source.get(offset)) << 24 | Byte.toUnsignedInt(source.get(offset + 1)) << 16 | Byte.toUnsignedInt(source.get(offset + 2)) << 8 | Byte.toUnsignedInt(source.get(offset + 3)); }
  private static long getLong(ByteBuffer source, int offset) { return (long) getInt(source, offset) << 32 | Integer.toUnsignedLong(getInt(source, offset + Integer.BYTES)); }
}
