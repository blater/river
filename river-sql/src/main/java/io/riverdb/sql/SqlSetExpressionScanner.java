package io.riverdb.sql;

/** Quote-aware boundaries for SELECT set operators and their root tail. */
final class SqlSetExpressionScanner {
  private SqlSetExpressionScanner() { }

  static boolean contains(CharSequence sql, int start, int end) {
    start = skipSpaces(sql, start, end);
    end = trimEnd(sql, start, end);
    if (topLevel(sql, start, end, "UNION") >= 0) return true;
    if (start >= end || sql.charAt(start) != '(') return false;
    int close = SqlNestedSubqueryScanner.matchingClose(sql, start, end);
    return close > start && contains(sql, start + 1, close);
  }

  static int topLevel(CharSequence sql, int start, int end, String keyword) {
    return SqlNestedSubqueryScanner.topLevelKeyword(sql, start, end, keyword);
  }

  static int tail(CharSequence sql, int afterUnion, int end) {
    int order = followedByKeyword(sql, afterUnion, end, "ORDER", "BY");
    int limit = followedByNumber(sql, afterUnion, end, "LIMIT");
    if (order < 0) return limit;
    return limit < 0 ? order : Math.min(order, limit);
  }

  static boolean keyword(CharSequence sql, int start, int end, String keyword) {
    return SqlNestedSubqueryScanner.keywordAt(sql, start, end, keyword);
  }

  private static int followedByKeyword(
      CharSequence sql, int start, int end, String first, String second) {
    int candidate = topLevel(sql, start, end, first);
    while (candidate >= 0) {
      int next = skipSpaces(sql, candidate + first.length(), end);
      if (keyword(sql, next, end, second)) return candidate;
      candidate = topLevel(sql, candidate + first.length(), end, first);
    }
    return -1;
  }

  private static int followedByNumber(
      CharSequence sql, int start, int end, String keyword) {
    int candidate = topLevel(sql, start, end, keyword);
    while (candidate >= 0) {
      int next = skipSpaces(sql, candidate + keyword.length(), end);
      if (next < end && (SqlParserInput.digit(sql.charAt(next))
          || sql.charAt(next) == '-' || sql.charAt(next) == '?')) return candidate;
      candidate = topLevel(sql, candidate + keyword.length(), end, keyword);
    }
    return -1;
  }

  static int skipSpaces(CharSequence sql, int start, int end) {
    int index = start;
    while (index < end && Character.isWhitespace(sql.charAt(index))) index++;
    return index;
  }

  static int trimEnd(CharSequence sql, int start, int end) {
    int index = end;
    while (index > start && Character.isWhitespace(sql.charAt(index - 1))) index--;
    if (index > start && sql.charAt(index - 1) == ';') {
      index--;
      while (index > start && Character.isWhitespace(sql.charAt(index - 1))) index--;
    }
    return index;
  }
}
