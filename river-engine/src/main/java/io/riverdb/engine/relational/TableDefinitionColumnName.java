package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Reusable bounded column-name view owned by a table definition. */
final class TableDefinitionColumnName implements CharSequence {
  private final char[] characters = new char[TableSchema.MAXIMUM_NAME_LENGTH];
  private int length;

  void reset() {
    length = 0;
  }

  void set(CharSequence name) {
    length = name.length();
    for (int index = 0; index < length; index++) {
      characters[index] = name.charAt(index);
    }
  }

  void set(ByteBuffer source, int offset, int bytes) {
    length = bytes;
    for (int index = 0; index < length; index++) {
      characters[index] = (char) Byte.toUnsignedInt(source.get(offset + index));
    }
  }

  boolean matches(CharSequence name) {
    if (name == null || name.length() != length) {
      return false;
    }
    for (int index = 0; index < length; index++) {
      if (name.charAt(index) != characters[index]) {
        return false;
      }
    }
    return true;
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
