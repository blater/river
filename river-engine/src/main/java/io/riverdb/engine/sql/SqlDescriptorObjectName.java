package io.riverdb.engine.sql;

/** Reusable bounded SQL object-name copy. */
final class SqlDescriptorObjectName implements CharSequence {
  private final char[] chars = new char[io.riverdb.sql.SqlIdentifier.MAXIMUM_LENGTH];
  private int length;

  void set(CharSequence value) {
    length = value.length();
    for (int index = 0; index < length; index++) chars[index] = value.charAt(index);
  }

  void reset() {
    for (int index = 0; index < length; index++) chars[index] = 0;
    length = 0;
  }

  @Override public int length() { return length; }
  @Override public char charAt(int index) { return chars[index]; }
  @Override public CharSequence subSequence(int start, int end) {
    return new String(chars, start, end - start);
  }
}
