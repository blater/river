package io.riverdb.base.error;

/**
 * Caller-owned bounded status text. A caller creates one instance, resets it, and reuses it across
 * operations. Engine code appends fixed fragments and primitive values without formatting objects.
 */
public final class StatusDetail implements CharSequence {
  private final char[] characters;
  private StatusCode code = StatusCode.OK;
  private int length;
  private boolean truncated;

  public StatusDetail(int capacity) {
    if (capacity < 0) {
      throw new IllegalArgumentException("status detail capacity must be non-negative");
    }
    characters = new char[capacity];
  }

  public StatusDetail reset() {
    code = StatusCode.OK;
    length = 0;
    truncated = false;
    return this;
  }

  public StatusDetail set(StatusCode newCode) {
    code = newCode;
    length = 0;
    truncated = false;
    return this;
  }

  public StatusCode code() {
    return code;
  }

  public int capacity() {
    return characters.length;
  }

  public boolean truncated() {
    return truncated;
  }

  public StatusDetail append(char value) {
    if (length < characters.length) {
      characters[length++] = value;
    } else {
      truncated = true;
    }
    return this;
  }

  public StatusDetail append(CharSequence value) {
    for (int index = 0; index < value.length(); index++) {
      append(value.charAt(index));
    }
    return this;
  }

  public StatusDetail append(long value) {
    if (value == Long.MIN_VALUE) {
      return append("-9223372036854775808");
    }
    if (value < 0) {
      append('-');
      value = -value;
    }
    int digits = decimalDigits(value);
    int originalLength = length;
    int writable = Math.min(digits, characters.length - length);
    length += writable;
    if (writable != digits) {
      truncated = true;
    }
    long remaining = value;
    for (int offset = digits - 1; offset >= 0; offset--) {
      int target = originalLength + offset;
      if (target < characters.length) {
        characters[target] = (char) ('0' + remaining % 10);
      }
      remaining /= 10;
    }
    return this;
  }

  private static int decimalDigits(long value) {
    int digits = 1;
    while (value >= 10) {
      value /= 10;
      digits++;
    }
    return digits;
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
    if (start < 0 || end > length || start > end) {
      throw new IndexOutOfBoundsException();
    }
    return new String(characters, start, end - start);
  }

  public String asString() {
    return new String(characters, 0, length);
  }

  public int copyTo(char[] destination, int destinationOffset) {
    System.arraycopy(characters, 0, destination, destinationOffset, length);
    return length;
  }

  @Override
  public String toString() {
    return asString();
  }
}
