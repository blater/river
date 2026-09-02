package io.riverdb.base.type;

/** Canonical finite IEEE representations used by rows, keys, results, and wire values. */
public final class SqlApproximateNumeric {
  private static final int NEGATIVE_REAL_ZERO = Float.floatToRawIntBits(-0.0f);
  private static final long NEGATIVE_DOUBLE_ZERO = Double.doubleToRawLongBits(-0.0d);

  private SqlApproximateNumeric() { }

  public static long realBits(float value) {
    if (!Float.isFinite(value)) return -1;
    int bits = value == 0.0f ? 0 : Float.floatToRawIntBits(value);
    return Integer.toUnsignedLong(bits);
  }

  public static long doubleBits(double value) {
    if (!Double.isFinite(value)) return NEGATIVE_DOUBLE_ZERO;
    return value == 0.0d ? 0 : Double.doubleToRawLongBits(value);
  }

  public static boolean validRealBits(long bits) {
    if (bits >>> Integer.SIZE != 0 || (int) bits == NEGATIVE_REAL_ZERO) return false;
    return Float.isFinite(Float.intBitsToFloat((int) bits));
  }

  public static boolean validDoubleBits(long bits) {
    return bits != NEGATIVE_DOUBLE_ZERO && Double.isFinite(Double.longBitsToDouble(bits));
  }

  public static long sortableBits(int descriptor, long bits) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    if (type == SqlTypeDescriptor.TYPE_ID_REAL && validRealBits(bits)) {
      int value = (int) bits;
      return Integer.toUnsignedLong(value < 0 ? ~value : value ^ Integer.MIN_VALUE);
    }
    if (type == SqlTypeDescriptor.TYPE_ID_DOUBLE && validDoubleBits(bits)) {
      return bits < 0 ? ~bits : bits ^ Long.MIN_VALUE;
    }
    return 0;
  }

  public static long valueBits(int descriptor, long sortable) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    if (type == SqlTypeDescriptor.TYPE_ID_REAL && sortable >>> Integer.SIZE == 0) {
      int value = (int) sortable;
      return Integer.toUnsignedLong(value < 0 ? value ^ Integer.MIN_VALUE : ~value);
    }
    if (type == SqlTypeDescriptor.TYPE_ID_DOUBLE) {
      return sortable < 0 ? sortable ^ Long.MIN_VALUE : ~sortable;
    }
    return 0;
  }
}
