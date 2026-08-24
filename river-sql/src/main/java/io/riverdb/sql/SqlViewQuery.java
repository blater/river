package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Bounded UTF-16 view text owned by a parsed SQL command. */
final class SqlViewQuery implements CharSequence {
  private final char[] characters = new char[SqlCommand.MAXIMUM_VIEW_QUERY_LENGTH];
  private int length;

  StatusCode set(CharSequence source, int start, int end) {
    if (source == null
        || start < 0
        || end <= start
        || end > source.length()
        || end - start > characters.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = start; index < end; index++) {
      char character = source.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (++index >= end || !Character.isLowSurrogate(source.charAt(index))) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        characters[index - start - 1] = character;
        characters[index - start] = source.charAt(index);
      } else if (Character.isLowSurrogate(character)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      } else {
        characters[index - start] = character;
      }
    }
    length = end - start;
    return StatusCode.OK;
  }

  void reset() { length = 0; }

  @Override
  public int length() { return length; }

  @Override
  public char charAt(int index) {
    if (index < 0 || index >= length) {
      throw new IndexOutOfBoundsException(index);
    }
    return characters[index];
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    throw new UnsupportedOperationException();
  }
}
