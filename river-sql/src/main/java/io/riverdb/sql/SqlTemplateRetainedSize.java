package io.riverdb.sql;

/** Overflow-safe conservative sizing for memory owned by a frozen SQL template. */
final class SqlTemplateRetainedSize {
  static final int REFERENCE_BYTES = Long.BYTES;
  private static final long ARRAY_HEADER_BYTES = 16;
  private static final long STRING_OBJECT_BYTES = 24;
  private static final long ALIGNMENT = 8;

  private SqlTemplateRetainedSize() { }

  static long array(int length, int elementBytes) {
    if (length < 0 || elementBytes <= 0
        || length > (Long.MAX_VALUE - ARRAY_HEADER_BYTES) / elementBytes) {
      return Long.MAX_VALUE;
    }
    return align(ARRAY_HEADER_BYTES + (long) length * elementBytes);
  }

  static long strings(String[] values) {
    long bytes = array(values.length, REFERENCE_BYTES);
    for (String value : values) bytes = add(bytes, string(value));
    return bytes;
  }

  static long string(String value) {
    return string((CharSequence) value);
  }

  static long string(CharSequence value) {
    return add(STRING_OBJECT_BYTES, array(value.length(), Character.BYTES));
  }

  static long add(long left, long right) {
    return left < 0 || right < 0 || left > Long.MAX_VALUE - right
        ? Long.MAX_VALUE : left + right;
  }

  static long add(long total, long first, long second) {
    return add(add(total, first), second);
  }

  private static long align(long bytes) {
    return bytes > Long.MAX_VALUE - (ALIGNMENT - 1)
        ? Long.MAX_VALUE : bytes + (ALIGNMENT - 1) & -ALIGNMENT;
  }
}
