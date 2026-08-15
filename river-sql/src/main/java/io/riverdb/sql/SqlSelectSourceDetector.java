package io.riverdb.sql;

/** Finds the row-source FROM while ignoring literals and nested expressions. */
final class SqlSelectSourceDetector {
  private SqlSelectSourceDetector() {
  }

  static boolean hasRowSource(CharSequence sql, int start) {
    int depth = 0;
    boolean quoted = false;
    for (int index = start; index < sql.length(); index++) {
      char character = sql.charAt(index);
      if (quoted) {
        if (character == '\'' && index + 1 < sql.length()
            && sql.charAt(index + 1) == '\'') {
          index++;
        } else if (character == '\'') {
          quoted = false;
        }
        continue;
      }
      if (character == '\'') {
        quoted = true;
      } else if (character == '(') {
        depth++;
      } else if (character == ')') {
        depth--;
      } else if (depth == 0 && keywordAt(sql, index, "FROM")) {
        return true;
      }
    }
    return false;
  }

  private static boolean keywordAt(
      CharSequence sql, int offset, String keyword) {
    int end = offset + keyword.length();
    if (end > sql.length()
        || offset > 0 && identifierPart(sql.charAt(offset - 1))
        || end < sql.length() && identifierPart(sql.charAt(end))) {
      return false;
    }
    for (int index = 0; index < keyword.length(); index++) {
      char actual = sql.charAt(offset + index);
      char expected = keyword.charAt(index);
      if (actual != expected && actual != expected + ('a' - 'A')) {
        return false;
      }
    }
    return true;
  }

  private static boolean identifierPart(char character) {
    return SqlParserInput.identifierStart(character)
        || character >= '0' && character <= '9';
  }
}
