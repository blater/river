package io.riverdb.sql;

/** Quote-aware scanner for nested SELECT boundaries and surrounding operators. */
final class SqlNestedSubqueryScanner {
  private SqlNestedSubqueryScanner() { }

  static int firstSelectOpening(CharSequence sql, int start, int end) {
    boolean quoted = false;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '\'' && quoted && index + 1 < end && sql.charAt(index + 1) == '\'') index++;
      else if (character == '\'') quoted = !quoted;
      else if (!quoted && character == '(') {
        int select = index + 1;
        while (select < end && Character.isWhitespace(sql.charAt(select))) select++;
        if (keywordAt(sql, select, end, "SELECT")) return index;
      }
    }
    return -1;
  }

  static int matchingClose(CharSequence sql, int opening, int end) {
    int depth = 0;
    boolean quoted = false;
    for (int index = opening; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '\'' && quoted && index + 1 < end && sql.charAt(index + 1) == '\'') index++;
      else if (character == '\'') quoted = !quoted;
      else if (!quoted && character == '(') depth++;
      else if (!quoted && character == ')' && --depth == 0) return index;
    }
    return -1;
  }

  static int topLevelKeyword(CharSequence sql, int start, int end, String keyword) {
    int depth = 0;
    boolean quoted = false;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '\'' && quoted && index + 1 < end && sql.charAt(index + 1) == '\'') index++;
      else if (character == '\'') quoted = !quoted;
      else if (!quoted && character == '(') depth++;
      else if (!quoted && character == ')') depth--;
      else if (!quoted && depth == 0 && keywordAt(sql, index, end, keyword)) return index;
    }
    return -1;
  }

  static int kind(CharSequence sql, int start, int opening, int close, int end) {
    if (priorKeywordStart(sql, start, opening, "EXISTS") >= 0) return SqlQuery.SUBQUERY_EXISTS;
    if (priorKeywordStart(sql, start, opening, "IN") >= 0) return SqlQuery.SUBQUERY_MEMBERSHIP;
    return priorComparison(sql, start, opening) || followingComparison(sql, close + 1, end)
        ? SqlQuery.SUBQUERY_SCALAR : 0;
  }

  private static boolean followingComparison(CharSequence sql, int start, int end) {
    int index = start;
    while (index < end && Character.isWhitespace(sql.charAt(index))) index++;
    if (index >= end) return false;
    char character = sql.charAt(index);
    return character == '=' || character == '<' || character == '>'
        || character == '!' && index + 1 < end && sql.charAt(index + 1) == '=';
  }

  private static boolean priorComparison(CharSequence sql, int start, int opening) {
    int index = opening;
    while (index > start && Character.isWhitespace(sql.charAt(index - 1))) index--;
    if (index <= start) return false;
    char character = sql.charAt(index - 1);
    return character == '=' || character == '<' || character == '>';
  }

  static int priorKeywordStart(CharSequence sql, int start, int opening, String keyword) {
    int end = opening;
    while (end > start && Character.isWhitespace(sql.charAt(end - 1))) end--;
    int word = end - keyword.length();
    if (word < start) return -1;
    for (int index = 0; index < keyword.length(); index++) {
      if (SqlParserInput.upper(sql.charAt(word + index)) != keyword.charAt(index)) return -1;
    }
    return word == start || !identifierPart(sql.charAt(word - 1)) ? word : -1;
  }

  static boolean keywordAt(CharSequence sql, int start, int end, String keyword) {
    if (start < 0 || end - start < keyword.length()
        || start > 0 && identifierPart(sql.charAt(start - 1))) return false;
    for (int index = 0; index < keyword.length(); index++) {
      if (SqlParserInput.upper(sql.charAt(start + index)) != keyword.charAt(index)) return false;
    }
    int next = start + keyword.length();
    return next >= end || !identifierPart(sql.charAt(next));
  }

  private static boolean identifierPart(char character) {
    return SqlParserInput.identifierStart(character) || SqlParserInput.digit(character);
  }
}
