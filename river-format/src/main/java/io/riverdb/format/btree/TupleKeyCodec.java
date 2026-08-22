package io.riverdb.format.btree;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.nio.ByteBuffer;

/** Canonical order-preserving typed tuple keys with a stable logical-row tie-break. */
public final class TupleKeyCodec {
  public static final int VERSION = 1;
  public static final int MAXIMUM_ARITY = 4;
  public static final int MAXIMUM_KEY_BYTES = 4_096;
  public static final int HEADER_BYTES = 4;
  public static final int LOGICAL_ROW_ID_BYTES = Long.BYTES;

  static final int NULL_VALUE = 0;
  static final int PRESENT_VALUE = 1;

  private TupleKeyCodec() {
  }

  public static boolean validate(ByteBuffer source, int offset, int length) {
    if (source == null
        || offset < 0
        || length < HEADER_BYTES + 2 + LOGICAL_ROW_ID_BYTES
        || length > MAXIMUM_KEY_BYTES
        || source.limit() - offset < length
        || Byte.toUnsignedInt(source.get(offset)) != VERSION) {
      return false;
    }
    int arity = Byte.toUnsignedInt(source.get(offset + 1));
    if (arity <= 0
        || arity > MAXIMUM_ARITY
        || source.get(offset + 2) != 0
        || source.get(offset + 3) != 0) {
      return false;
    }
    int cursor = offset + HEADER_BYTES;
    int valuesEnd = offset + length - LOGICAL_ROW_ID_BYTES;
    for (int column = 0; column < arity; column++) {
      if (valuesEnd - cursor < 2) return false;
      int type = Byte.toUnsignedInt(source.get(cursor++));
      int marker = Byte.toUnsignedInt(source.get(cursor++));
      if (!validType(type) || marker != NULL_VALUE && marker != PRESENT_VALUE) return false;
      if (marker == NULL_VALUE) continue;
      if (type == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        boolean terminated = false;
        int scalars = 0;
        while (valuesEnd - cursor >= Integer.BYTES) {
          int encoded = getBigEndianInt(source, cursor);
          cursor += Integer.BYTES;
          if (encoded == 0) {
            terminated = true;
            break;
          }
          int scalar = encoded - 1;
          if (!Character.isValidCodePoint(scalar)
              || scalar >= Character.MIN_SURROGATE && scalar <= Character.MAX_SURROGATE) {
            return false;
          }
          if (++scalars > SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS) return false;
        }
        if (!terminated) return false;
      } else {
        if (valuesEnd - cursor < Long.BYTES) return false;
        long value = getBigEndianLong(source, cursor) ^ Long.MIN_VALUE;
        if (!SqlValueDomain.validFixed(globalDescriptor(type), value)) return false;
        cursor += Long.BYTES;
      }
    }
    return cursor == valuesEnd && getBigEndianLong(source, valuesEnd) > 0;
  }

  public static long logicalRowId(ByteBuffer source, int offset, int length) {
    return validate(source, offset, length)
        ? getBigEndianLong(source, offset + length - LOGICAL_ROW_ID_BYTES) : 0;
  }

  public static boolean validShape(
      int arity, int first, int second, int third, int fourth) {
    return arity > 0
        && arity <= MAXIMUM_ARITY
        && SqlTypeDescriptor.isValid(first)
        && (arity >= 2 ? SqlTypeDescriptor.isValid(second) : second == 0)
        && (arity >= 3 ? SqlTypeDescriptor.isValid(third) : third == 0)
        && (arity >= 4 ? SqlTypeDescriptor.isValid(fourth) : fourth == 0);
  }

  public static boolean matchesShape(
      ByteBuffer source,
      int offset,
      int length,
      int arity,
      int first,
      int second,
      int third,
      int fourth) {
    if (!validate(source, offset, length)
        || Byte.toUnsignedInt(source.get(offset + 1)) != arity
        || !validShape(arity, first, second, third, fourth)) {
      return false;
    }
    int cursor = offset + HEADER_BYTES;
    int valuesEnd = offset + length - LOGICAL_ROW_ID_BYTES;
    for (int column = 0; column < arity; column++) {
      int type = Byte.toUnsignedInt(source.get(cursor++));
      int descriptor = descriptorAt(column, first, second, third, fourth);
      if (type != SqlTypeDescriptor.typeId(descriptor)) return false;
      int marker = Byte.toUnsignedInt(source.get(cursor++));
      if (marker == NULL_VALUE) continue;
      if (type == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        int scalars = 0;
        while (getBigEndianInt(source, cursor) != 0) {
          cursor += Integer.BYTES;
          scalars++;
        }
        cursor += Integer.BYTES;
        if (scalars > SqlTypeDescriptor.parameterOne(descriptor)) return false;
      } else {
        long value = getBigEndianLong(source, cursor) ^ Long.MIN_VALUE;
        if (!SqlValueDomain.validFixed(descriptor, value)) return false;
        cursor += Long.BYTES;
      }
    }
    return cursor == valuesEnd;
  }

  private static int descriptorAt(
      int index, int first, int second, int third, int fourth) {
    return switch (index) {
      case 0 -> first;
      case 1 -> second;
      case 2 -> third;
      case 3 -> fourth;
      default -> 0;
    };
  }

  /** Unsigned lexicographic comparison of two already validated tuple keys. */
  public static int compare(
      ByteBuffer left,
      int leftOffset,
      int leftLength,
      ByteBuffer right,
      int rightOffset,
      int rightLength) {
    int shared = Math.min(leftLength, rightLength);
    for (int index = 0; index < shared; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(left.get(leftOffset + index)),
          Byte.toUnsignedInt(right.get(rightOffset + index)));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(leftLength, rightLength);
  }

  /** Compares only declared user components, excluding the logical-row tie-break. */
  public static int compareUserTuple(
      ByteBuffer left,
      int leftOffset,
      int leftLength,
      ByteBuffer right,
      int rightOffset,
      int rightLength) {
    return compare(
        left, leftOffset, leftLength - LOGICAL_ROW_ID_BYTES,
        right, rightOffset, rightLength - LOGICAL_ROW_ID_BYTES);
  }

  static void putBigEndianInt(ByteBuffer target, int offset, int value) {
    target.put(offset, (byte) (value >>> 24));
    target.put(offset + 1, (byte) (value >>> 16));
    target.put(offset + 2, (byte) (value >>> 8));
    target.put(offset + 3, (byte) value);
  }

  static void putBigEndianLong(ByteBuffer target, int offset, long value) {
    putBigEndianInt(target, offset, (int) (value >>> 32));
    putBigEndianInt(target, offset + Integer.BYTES, (int) value);
  }

  private static int getBigEndianInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset)) << 24
        | Byte.toUnsignedInt(source.get(offset + 1)) << 16
        | Byte.toUnsignedInt(source.get(offset + 2)) << 8
        | Byte.toUnsignedInt(source.get(offset + 3));
  }

  private static long getBigEndianLong(ByteBuffer source, int offset) {
    return (long) getBigEndianInt(source, offset) << 32
        | Integer.toUnsignedLong(getBigEndianInt(source, offset + Integer.BYTES));
  }

  private static boolean validType(int type) {
    return type >= SqlTypeDescriptor.TYPE_ID_BIGINT
        && type <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }

  private static int globalDescriptor(int type) {
    return switch (type) {
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> SqlTypeDescriptor.BIGINT;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> SqlTypeDescriptor.BOOLEAN;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 0);
      case SqlTypeDescriptor.TYPE_ID_DATE -> SqlTypeDescriptor.DATE;
      case SqlTypeDescriptor.TYPE_ID_TIME ->
          SqlTypeDescriptor.time(SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          SqlTypeDescriptor.timestamp(SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          SqlTypeDescriptor.timestampWithTimeZone(SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION);
      default -> 0;
    };
  }
}
