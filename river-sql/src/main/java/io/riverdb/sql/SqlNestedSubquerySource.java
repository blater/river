package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Quoted-text-safe discovery and synthetic source ownership for one subquery block. */
final class SqlNestedSubquerySource implements CharSequence, SqlParameterOrdinalSource {
  private final int[] starts = new int[SqlQuery.MAXIMUM_EDGES];
  private final int[] opens = new int[SqlQuery.MAXIMUM_EDGES];
  private final int[] closes = new int[SqlQuery.MAXIMUM_EDGES];
  private final int[] kinds = new int[SqlQuery.MAXIMUM_EDGES];
  private CharSequence source;
  private int start;
  private int firstEdge;
  private int count;
  private int length;

  boolean contains(CharSequence sql, int from, int to) {
    return firstSelectOpening(sql, from, to) >= 0;
  }

  StatusCode scan(
      CharSequence sql, int from, int to, SqlQuery query, int block, int[] offsets) {
    firstEdge = query.edgeCount();
    count = 0;
    int where = topLevelKeyword(sql, from, to, "WHERE");
    int opening = firstSelectOpening(sql, from, to);
    while (opening >= 0) {
      if (where < 0 || opening < where) return StatusCode.FEATURE_NOT_SUPPORTED;
      int close = matchingClose(sql, opening, to);
      if (close < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      int kind = kind(sql, from, opening, close, to);
      if (kind == 0) return StatusCode.FEATURE_NOT_SUPPORTED;
      if (count >= SqlBooleanPredicateProgram.MAXIMUM_LEAVES) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      int edge = query.addSubqueryEdge(block, kind);
      if (edge < 0) return StatusCode.QUERY_TOO_COMPLEX;
      int replacement = kind == SqlQuery.SUBQUERY_EXISTS
          ? priorKeywordStart(sql, from, opening, "EXISTS") : opening;
      if (replacement < from || count > 0 && replacement <= closes[edge - 1]) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      starts[edge] = replacement;
      opens[edge] = opening;
      closes[edge] = close;
      kinds[edge] = kind;
      count++;
      opening = firstSelectOpening(sql, close + 1, to);
    }
    set(sql, from, to, offsets);
    return count == 0 ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }

  int firstEdge() { return firstEdge; }
  int count() { return count; }
  int kindAt(int index) { return kinds[firstEdge + index]; }
  int childStart(int edge) { return opens[edge] + 1; }
  int childEnd(int edge) { return closes[edge]; }

  @Override public int length() { return length; }

  @Override
  public char charAt(int index) {
    if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
    int output = 0;
    int original = start;
    for (int item = 0; item < count; item++) {
      int edge = firstEdge + item;
      int plain = starts[edge] - original;
      if (index < output + plain) return source.charAt(original + index - output);
      output += plain;
      int replacement = replacementLength(kinds[edge]);
      if (index < output + replacement) {
        return replacementCharacter(kinds[edge], index - output);
      }
      output += replacement;
      original = closes[edge] + 1;
    }
    return source.charAt(original + index - output);
  }

  @Override
  public int parameterOrdinal(int offset) {
    int original = originalOffset(offset);
    if (original < 0 || source.charAt(original) != '?') return -1;
    return SqlParameterOrdinalSource.ordinal(source, original);
  }

  @Override
  public CharSequence subSequence(int from, int to) {
    throw new UnsupportedOperationException();
  }

  private void set(CharSequence sql, int from, int to, int[] offsets) {
    source = sql;
    start = from;
    length = to - from;
    int removed = 0;
    for (int index = 0; index < count; index++) {
      int edge = firstEdge + index;
      int replacement = replacementLength(kinds[edge]);
      offsets[index] = starts[edge] - start - removed
          + (kinds[edge] == SqlQuery.SUBQUERY_MEMBERSHIP ? 1 : 0);
      removed += closes[edge] + 1 - starts[edge] - replacement;
    }
    length -= removed;
  }

  private int originalOffset(int index) {
    if (index < 0 || index >= length) return -1;
    int output = 0;
    int original = start;
    for (int item = 0; item < count; item++) {
      int edge = firstEdge + item;
      int plain = starts[edge] - original;
      if (index < output + plain) return original + index - output;
      output += plain;
      int replacement = replacementLength(kinds[edge]);
      if (index < output + replacement) return -1;
      output += replacement;
      original = closes[edge] + 1;
    }
    return original + index - output;
  }

  private static int kind(
      CharSequence sql, int start, int opening, int close, int end) {
    if (priorKeywordStart(sql, start, opening, "EXISTS") >= 0) {
      return SqlQuery.SUBQUERY_EXISTS;
    }
    if (priorKeywordStart(sql, start, opening, "IN") >= 0) {
      return SqlQuery.SUBQUERY_MEMBERSHIP;
    }
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

  private static int priorKeywordStart(
      CharSequence sql, int start, int opening, String keyword) {
    int end = opening;
    while (end > start && Character.isWhitespace(sql.charAt(end - 1))) end--;
    int word = end - keyword.length();
    if (word < start) return -1;
    for (int index = 0; index < keyword.length(); index++) {
      if (SqlParserInput.upper(sql.charAt(word + index)) != keyword.charAt(index)) return -1;
    }
    return word == start || !identifierPart(sql.charAt(word - 1)) ? word : -1;
  }

  private static int firstSelectOpening(CharSequence sql, int start, int end) {
    boolean quoted = false;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '\'' && quoted && index + 1 < end && sql.charAt(index + 1) == '\'') {
        index++;
      } else if (character == '\'') quoted = !quoted;
      else if (!quoted && character == '(') {
        int select = index + 1;
        while (select < end && Character.isWhitespace(sql.charAt(select))) select++;
        if (keywordAt(sql, select, end, "SELECT")) return index;
      }
    }
    return -1;
  }

  private static int matchingClose(CharSequence sql, int opening, int end) {
    int depth = 0;
    boolean quoted = false;
    for (int index = opening; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '\'' && quoted && index + 1 < end && sql.charAt(index + 1) == '\'') {
        index++;
      } else if (character == '\'') quoted = !quoted;
      else if (!quoted && character == '(') depth++;
      else if (!quoted && character == ')' && --depth == 0) return index;
    }
    return -1;
  }

  private static int topLevelKeyword(
      CharSequence sql, int start, int end, String keyword) {
    int depth = 0;
    boolean quoted = false;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '\'' && quoted && index + 1 < end && sql.charAt(index + 1) == '\'') {
        index++;
      } else if (character == '\'') quoted = !quoted;
      else if (!quoted && character == '(') depth++;
      else if (!quoted && character == ')') depth--;
      else if (!quoted && depth == 0 && keywordAt(sql, index, end, keyword)) return index;
    }
    return -1;
  }

  private static boolean keywordAt(
      CharSequence sql, int start, int end, String keyword) {
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

  private static int replacementLength(int kind) {
    return kind == SqlQuery.SUBQUERY_EXISTS ? 4
        : kind == SqlQuery.SUBQUERY_MEMBERSHIP ? 3 : 1;
  }

  private static char replacementCharacter(int kind, int offset) {
    if (kind == SqlQuery.SUBQUERY_EXISTS) return "TRUE".charAt(offset);
    if (kind == SqlQuery.SUBQUERY_MEMBERSHIP) return offset == 0 ? '('
        : offset == 1 ? '0' : ')';
    return '0';
  }
}
