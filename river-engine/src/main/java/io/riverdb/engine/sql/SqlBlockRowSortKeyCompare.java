package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Exact comparison of two validated canonical block sort keys. */
final class SqlBlockRowSortKeyCompare {
  private SqlBlockRowSortKeyCompare() {}

  static int compare(
      ByteBuffer left, ByteBuffer right, SqlBlockRowSortKeyCodec shape) {
    int leftAt = 0;
    int rightAt = 0;
    for (int part = 0; part < shape.partCount(); part++) {
      int leftMarker = Byte.toUnsignedInt(left.get(leftAt++));
      int rightMarker = Byte.toUnsignedInt(right.get(rightAt++));
      int compared = Integer.compare(leftMarker, rightMarker);
      if (leftMarker != 0 && rightMarker != 0) {
        int descriptor = shape.descriptor(part);
        if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
          int leftLength = left.getInt(leftAt);
          int rightLength = right.getInt(rightAt);
          leftAt += Integer.BYTES;
          rightAt += Integer.BYTES;
          compared = compareBytes(left, leftAt, leftLength, right, rightAt, rightLength);
          leftAt += leftLength;
          rightAt += rightLength;
        } else {
          boolean wide = SqlTypeDescriptor.isWideDecimal(descriptor);
          long leftHigh = wide ? left.getLong(leftAt) : 0;
          long rightHigh = wide ? right.getLong(rightAt) : 0;
          leftAt += wide ? Long.BYTES : 0;
          rightAt += wide ? Long.BYTES : 0;
          long leftValue = left.getLong(leftAt);
          long rightValue = right.getLong(rightAt);
          leftAt += Long.BYTES;
          rightAt += Long.BYTES;
          compared = wide ? compare128(leftHigh, leftValue, rightHigh, rightValue)
              : SqlNumericTypeRules.isNumeric(descriptor)
              ? SqlNumericValue.compare(leftValue, descriptor, rightValue, descriptor)
              : Long.compare(leftValue, rightValue);
        }
      }
      if (compared != 0) return shape.descending(part) ? -compared : compared;
    }
    return 0;
  }

  private static int compareBytes(
      ByteBuffer left, int leftAt, int leftLength,
      ByteBuffer right, int rightAt, int rightLength) {
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(
          Byte.toUnsignedInt(left.get(leftAt + index)),
          Byte.toUnsignedInt(right.get(rightAt + index)));
      if (compared != 0) return compared;
    }
    return Integer.compare(leftLength, rightLength);
  }

  private static int compare128(long lh, long ll, long rh, long rl) {
    int high = Long.compare(lh, rh);
    return high != 0 ? high : Long.compareUnsigned(ll, rl);
  }
}
