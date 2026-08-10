package io.riverdb.sql;

/** Fixed-capacity caller-owned ASCII identifier. */
public final class SqlIdentifier implements CharSequence {
  public static final int MAXIMUM_LENGTH = 64;

  private final char[] characters = new char[MAXIMUM_LENGTH];
  private int length;

  void reset() {
    length = 0;
  }

  void append(char character) {
    characters[length++] = character;
  }

  @Override
  public int length() {
    return length;
  }

  @Override
  public char charAt(int index) {
    if (index < 0 || index >= length) {
      throw new IndexOutOfBoundsException(index);
    }
    return characters[index];
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    if (start < 0 || end < start || end > length) {
      throw new IndexOutOfBoundsException(start);
    }
    return new String(characters, start, end - start);
  }
}
