package io.riverdb.base.tuple;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Canonical byte sizing shared by tuple descriptors and their format implementation. */
public final class TupleEncodingSize {
  public static final int PHYSICAL_SUFFIX_BYTES = Long.BYTES;
  private static final int OBJECT_HEADER_BYTES = 32;
  private static final int ARRAY_HEADER_BYTES = 16;
  private static final int TUPLE_PREFIX_BYTES = 2;
  private static final int PART_PREFIX_BYTES = 2;
  private static final int FIXED_VALUE_BYTES = Long.BYTES;
  private static final int WIDE_DECIMAL_VALUE_BYTES = Long.BYTES * 2;
  private static final int TEXT_SCALAR_BYTES = Integer.BYTES;

  private TupleEncodingSize() { }

  public static int headerBytes(int partCount) {
    return partCount <= 0 || partCount > TupleShape.MAXIMUM_PARTS
        ? 0 : TUPLE_PREFIX_BYTES + unsignedVarIntBytes(partCount);
  }

  public static int maximumPartBytes(int descriptor) {
    if (!SqlTypeDescriptor.isValid(descriptor)) return 0;
    int valueBytes;
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      valueBytes = (SqlTypeDescriptor.parameterOne(descriptor) + 1) * TEXT_SCALAR_BYTES;
    } else {
      valueBytes = SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
          ? WIDE_DECIMAL_VALUE_BYTES : FIXED_VALUE_BYTES;
    }
    return PART_PREFIX_BYTES + valueBytes;
  }

  public static int physicalBytes(int userTupleBytes) {
    return userTupleBytes <= 0 || userTupleBytes > Integer.MAX_VALUE - PHYSICAL_SUFFIX_BYTES
        ? 0 : userTupleBytes + PHYSICAL_SUFFIX_BYTES;
  }

  static int maximumBytes(int[] descriptors) {
    int bytes = headerBytes(descriptors.length);
    for (int descriptor : descriptors) bytes += maximumPartBytes(descriptor);
    return bytes;
  }

  static long shapeRetainedBytes(int partCount) {
    return align(OBJECT_HEADER_BYTES + Long.BYTES * 3L + Integer.BYTES)
        + align(ARRAY_HEADER_BYTES + (long) Integer.BYTES * partCount);
  }

  private static int unsignedVarIntBytes(int value) {
    return value < 1 << 7 ? 1 : value < 1 << 14 ? 2 : 3;
  }

  private static long align(long bytes) {
    return bytes + 7 & ~7L;
  }
}
