package io.riverdb.sql;

/** Allocation helpers used only while freezing a prepared statement. */
final class SqlTemplateStrings {
  private SqlTemplateStrings() { }

  static String copy(CharSequence source) {
    char[] characters = new char[source.length()];
    for (int index = 0; index < characters.length; index++) {
      characters[index] = source.charAt(index);
    }
    return new String(characters);
  }

  static String[] copy(SqlIdentifier[] source, int count) {
    String[] values = new String[count];
    for (int index = 0; index < count; index++) values[index] = copy(source[index]);
    return values;
  }
}
