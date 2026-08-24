package io.riverdb.format.btree;

import io.riverdb.base.type.SqlTypeDescriptor;
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

  private TupleKeyCodec() { }

  public static boolean validate(ByteBuffer source, int offset, int length) {
    return TupleKeyValidation.validate(source, offset, length);
  }

  public static long logicalRowId(ByteBuffer source, int offset, int length) {
    return validate(source, offset, length) ? getBigEndianLong(source, offset + length - Long.BYTES) : 0;
  }

  public static boolean validShape(int arity, int first, int second, int third, int fourth) {
    return arity > 0 && arity <= MAXIMUM_ARITY && SqlTypeDescriptor.isValid(first)
        && (arity >= 2 ? SqlTypeDescriptor.isValid(second) : second == 0)
        && (arity >= 3 ? SqlTypeDescriptor.isValid(third) : third == 0)
        && (arity >= 4 ? SqlTypeDescriptor.isValid(fourth) : fourth == 0);
  }

  public static boolean matchesShape(ByteBuffer source, int offset, int length, int arity,
      int first, int second, int third, int fourth) {
    return TupleKeyValidation.matchesShape(source, offset, length, arity, first, second, third, fourth);
  }

  public static int compare(ByteBuffer left, int leftOffset, int leftLength,
      ByteBuffer right, int rightOffset, int rightLength) {
    int shared = Math.min(leftLength, rightLength);
    for (int index = 0; index < shared; index++) {
      int comparison = Integer.compare(Byte.toUnsignedInt(left.get(leftOffset + index)),
          Byte.toUnsignedInt(right.get(rightOffset + index)));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(leftLength, rightLength);
  }

  public static int compareUserTuple(ByteBuffer left, int leftOffset, int leftLength,
      ByteBuffer right, int rightOffset, int rightLength) {
    return compare(left, leftOffset, leftLength - Long.BYTES, right, rightOffset, rightLength - Long.BYTES);
  }

  static void putBigEndianInt(ByteBuffer target, int offset, int value) {
    target.put(offset, (byte) (value >>> 24)); target.put(offset + 1, (byte) (value >>> 16));
    target.put(offset + 2, (byte) (value >>> 8)); target.put(offset + 3, (byte) value);
  }

  static void putBigEndianLong(ByteBuffer target, int offset, long value) {
    putBigEndianInt(target, offset, (int) (value >>> 32));
    putBigEndianInt(target, offset + Integer.BYTES, (int) value);
  }

  private static long getBigEndianLong(ByteBuffer source, int offset) {
    return (long) getBigEndianInt(source, offset) << 32
        | Integer.toUnsignedLong(getBigEndianInt(source, offset + Integer.BYTES));
  }

  private static int getBigEndianInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset)) << 24 | Byte.toUnsignedInt(source.get(offset + 1)) << 16
        | Byte.toUnsignedInt(source.get(offset + 2)) << 8 | Byte.toUnsignedInt(source.get(offset + 3));
  }
}
