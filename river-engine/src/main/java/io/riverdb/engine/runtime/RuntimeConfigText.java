package io.riverdb.engine.runtime;

/** ASCII whitespace rules used by the deliberately small properties grammar. */
final class RuntimeConfigText {
  private RuntimeConfigText() {}

  static int skipSpace(String value, int start, int end) {
    int index = start;
    while (index < end && asciiSpace(value.charAt(index))) index++;
    return index;
  }

  static int trimEnd(String value, int start, int end) {
    int index = end;
    while (index > start && asciiSpace(value.charAt(index - 1))) index--;
    return index;
  }

  static boolean asciiBlank(CharSequence value) {
    if (value.length() == 0) return true;
    for (int index = 0; index < value.length(); index++) {
      if (!asciiSpace(value.charAt(index))) return false;
    }
    return true;
  }

  private static boolean asciiSpace(char value) {
    return value == ' ' || value == '\t';
  }
}
