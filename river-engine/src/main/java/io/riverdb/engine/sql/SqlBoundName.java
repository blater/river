package io.riverdb.engine.sql;

/** Fixed-capacity statement-owned SQL name. */
final class SqlBoundName implements CharSequence {
  private final char[] value = new char[64];
  private int length;

  void copyFrom(CharSequence source) {
    length = Math.min(source == null ? 0 : source.length(), value.length);
    for (int index = 0; index < length; index++) value[index] = source.charAt(index);
  }

  @Override public int length() { return length; }

  @Override public char charAt(int index) {
    if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
    return value[index];
  }

  @Override public CharSequence subSequence(int start, int end) {
    if (start < 0 || end < start || end > length) {
      throw new IndexOutOfBoundsException(start);
    }
    return new String(value, start, end - start);
  }

  @Override public String toString() { return new String(value, 0, length); }
}
