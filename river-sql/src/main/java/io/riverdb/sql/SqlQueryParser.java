package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Discovers linear derived sources and canonical predicate-subquery graphs. */
final class SqlQueryParser {
  private final SqlParser statements;
  private final SqlNestedQueryParser nested;
  private final SourceView source = new SourceView();

  SqlQueryParser(SqlParser parser) {
    statements = parser;
    nested = new SqlNestedQueryParser(parser);
  }

  StatusCode parse(CharSequence sql, SqlQuery query, SqlCommand result) {
    query.reset();
    result.reset();
    int start = skipExplainPrefix(sql, query);
    if (start < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (findDerivedSource(sql, start, sql.length()) >= 0) {
      StatusCode status = parseDerivedBlocks(sql, start, sql.length(), query);
      return status.isOk() ? query.compileDerived(result) : status;
    }
    if (nested.hasPredicateSubquery(sql, start, sql.length())) {
      return nested.parse(sql, start, sql.length(), query, result);
    }
    source.set(sql, start, sql.length(), sql.length(), sql.length());
    return statements.parseQueryBlock(source, result);
  }

  StatusCode parseAppend(CharSequence sql, SqlQuery query, SqlCommand result) {
    if (sql == null || query == null || result == null
        || skipExplainPrefix(sql) != skipSpaces(sql, 0)
        || blockDepth(sql) < 0) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    result.reset();
    int start = query.blockCount();
    if (findDerivedSource(sql, 0, sql.length()) >= 0) {
      StatusCode status = parseDerivedBlocks(sql, 0, sql.length(), query);
      return status.isOk() ? result.copyBlockFrom(query.block(start)) : status;
    }
    source.set(sql, 0, sql.length(), sql.length(), sql.length());
    StatusCode status = statements.parseQueryBlock(source, result);
    if (!status.isOk()) return status;
    SqlCommand block = query.nextBlock();
    return block == null ? StatusCode.QUERY_TOO_COMPLEX : block.copyBlockFrom(result);
  }

  int blockDepth(CharSequence sql) {
    if (sql == null || skipExplainPrefix(sql) != skipSpaces(sql, 0)) return -1;
    int start = 0;
    int end = sql.length();
    int depth = 1;
    while (true) {
      int open = findDerivedSource(sql, start, end);
      if (open < 0) {
        return nested.hasPredicateSubquery(sql, start, end) ? -1 : depth;
      }
      int close = matchingCloseParenthesis(sql, open, end);
      if (close < 0) return -1;
      if (nested.hasPredicateSubquery(sql, start, open)
          || nested.hasPredicateSubquery(sql, close + 1, end)) return -1;
      depth++;
      start = open + 1;
      end = close;
    }
  }

  private StatusCode parseDerivedBlocks(
      CharSequence sql, int start, int end, SqlQuery query) {
    int open = findDerivedSource(sql, start, end);
    if (open < 0) {
      if (nested.hasPredicateSubquery(sql, start, end)) {
        return nested.parseAppend(sql, start, end, query);
      }
      SqlCommand block = query.nextBlock();
      if (block == null) return StatusCode.QUERY_TOO_COMPLEX;
      source.set(sql, start, end, end, end);
      return statements.parseQueryBlock(source, block);
    }
    SqlCommand block = query.nextBlock();
    if (block == null) return StatusCode.QUERY_TOO_COMPLEX;
    int close = matchingCloseParenthesis(sql, open, end);
    if (close < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    source.set(sql, start, open, close + 1, end);
    StatusCode status = statements.parseQueryBlock(source, block);
    return status.isOk() ? parseDerivedBlocks(sql, open + 1, close, query) : status;
  }

  private static int skipExplainPrefix(CharSequence sql) {
    int start = skipSpaces(sql, 0);
    if (!matchesKeyword(sql, start, sql.length(), "EXPLAIN")) return start;
    start = skipSpaces(sql, start + 7);
    if (matchesKeyword(sql, start, sql.length(), "ANALYZE")) {
      start = skipSpaces(sql, start + 7);
    }
    return start < sql.length() ? start : -1;
  }

  private static int skipExplainPrefix(CharSequence sql, SqlQuery query) {
    int start = skipSpaces(sql, 0);
    if (!matchesKeyword(sql, start, sql.length(), "EXPLAIN")) return start;
    start = skipSpaces(sql, start + 7);
    boolean analyze = matchesKeyword(sql, start, sql.length(), "ANALYZE");
    if (analyze) start = skipSpaces(sql, start + 7);
    if (start >= sql.length()) return -1;
    query.setExplain(analyze);
    return start;
  }

  private static int findDerivedSource(CharSequence sql, int start, int end) {
    int from = findTopLevelKeyword(sql, start, end, "FROM");
    int sourceStart = from < 0 ? -1 : skipSpaces(sql, from + 4);
    return sourceStart >= 0 && sourceStart < end && sql.charAt(sourceStart) == '('
        ? sourceStart : -1;
  }

  private static int findTopLevelKeyword(
      CharSequence sql, int start, int end, String keyword) {
    int depth = 0;
    boolean quoted = false;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '\'' && quoted && index + 1 < end && sql.charAt(index + 1) == '\'') {
        index++;
      } else if (character == '\'') quoted = !quoted;
      else if (!quoted && character == '(') depth++;
      else if (!quoted && character == ')') {
        if (depth-- <= 0) return -1;
      } else if (!quoted && depth == 0 && matchesKeyword(sql, index, end, keyword)) {
        return index;
      }
    }
    return -1;
  }

  private static int matchingCloseParenthesis(CharSequence sql, int open, int end) {
    int depth = 0;
    boolean quoted = false;
    for (int index = open; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '\'' && quoted && index + 1 < end && sql.charAt(index + 1) == '\'') {
        index++;
      } else if (character == '\'') quoted = !quoted;
      else if (!quoted && character == '(') depth++;
      else if (!quoted && character == ')' && --depth == 0) return index;
    }
    return -1;
  }

  private static boolean matchesKeyword(
      CharSequence sql, int start, int end, String keyword) {
    if (start < 0 || start > 0 && identifierPart(sql.charAt(start - 1))
        || end - start < keyword.length()) return false;
    for (int index = 0; index < keyword.length(); index++) {
      if (upper(sql.charAt(start + index)) != keyword.charAt(index)) return false;
    }
    int keywordEnd = start + keyword.length();
    return keywordEnd >= end || !identifierPart(sql.charAt(keywordEnd));
  }

  private static int skipSpaces(CharSequence sql, int start) {
    int index = start;
    while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) index++;
    return index;
  }

  private static char upper(char character) {
    return character >= 'a' && character <= 'z'
        ? (char) (character - ('a' - 'A')) : character;
  }

  private static boolean identifierPart(char character) {
    return character >= 'a' && character <= 'z'
        || character >= 'A' && character <= 'Z'
        || character >= '0' && character <= '9' || character == '_';
  }

  private static final class SourceView implements CharSequence, SqlParameterOrdinalSource {
    private CharSequence source;
    private int firstStart;
    private int firstLength;
    private int secondStart;
    private int secondLength;

    void set(CharSequence text, int firstFrom, int firstTo, int secondFrom, int secondTo) {
      source = text;
      firstStart = firstFrom;
      firstLength = firstTo - firstFrom;
      secondStart = secondFrom;
      secondLength = secondTo - secondFrom;
    }

    @Override public int length() { return firstLength + secondLength; }
    @Override public char charAt(int index) {
      if (index < 0 || index >= length()) throw new IndexOutOfBoundsException(index);
      return index < firstLength ? source.charAt(firstStart + index)
          : source.charAt(secondStart + index - firstLength);
    }
    @Override public int parameterOrdinal(int offset) {
      if (offset < 0 || offset >= length() || charAt(offset) != '?') return -1;
      int original = offset < firstLength ? firstStart + offset
          : secondStart + offset - firstLength;
      return SqlParameterOrdinalSource.ordinal(source, original);
    }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
