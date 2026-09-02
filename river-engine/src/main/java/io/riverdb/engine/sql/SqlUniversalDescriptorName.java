package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;

/** Fixed-capacity owned table name reused when a nested role scan reopens. */
final class SqlUniversalDescriptorName implements CharSequence {
  private final char[] value = new char[KeyDescriptor.MAXIMUM_NAME_LENGTH];
  private int length;

  StatusCode set(CharSequence source) {
    if (source == null || source.length() < 1 || source.length() > value.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    length = source.length();
    for (int index = 0; index < length; index++) value[index] = source.charAt(index);
    return StatusCode.OK;
  }

  void reset() { length = 0; }
  @Override public int length() { return length; }
  @Override public char charAt(int index) { return value[index]; }
  @Override public CharSequence subSequence(int start, int end) {
    throw new UnsupportedOperationException();
  }
}
