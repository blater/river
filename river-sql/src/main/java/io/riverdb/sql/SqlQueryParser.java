package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Discovers nested query topology and delegates each synthetic block to SqlParser. */
final class SqlQueryParser {
  private final SqlParser statements;
  private final SourceView source = new SourceView();
  private final ScalarSourceView scalarSource = new ScalarSourceView();
  private int existenceWhereStart = -1;
  private boolean existenceNegated;
  private int membershipOperatorStart = -1;
  private boolean membershipNegated;

  SqlQueryParser(SqlParser statementParser) {
    statements = statementParser;
  }

  StatusCode parse(CharSequence sql, SqlQuery query, SqlCommand result) {
    query.reset();
    result.reset();
    int start = skipExplainPrefix(sql, query);
    if (start < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int derived = findDerivedSource(sql, start, sql.length());
    if (derived >= 0) {
      StatusCode status = parseDerivedBlocks(sql, start, sql.length(), query);
      return status.isOk() ? query.compileDerived(result) : status;
    }
    int exists = findExistenceSource(sql, start, sql.length());
    if (exists >= 0) {
      return parseExistencePredicate(sql, start, exists, query, result);
    }
    int membership = findMembershipSource(sql, start, sql.length());
    if (membership >= 0) {
      return parseMembershipPredicate(sql, start, membership, query, result);
    }
    int scalar = findScalarSource(sql, start, sql.length());
    source.set(sql, start, sql.length(), sql.length(), sql.length());
    return scalar < 0
        ? statements.parseQueryBlock(source, result)
        : parseScalarPredicate(sql, start, scalar, query, result);
  }

  StatusCode parseAppend(CharSequence sql, SqlQuery query, SqlCommand result) {
    if (sql == null || query == null || result == null
        || skipExplainPrefix(sql) != skipSpaces(sql, 0)
        || findExistenceSource(sql, 0, sql.length()) >= 0
        || findMembershipSource(sql, 0, sql.length()) >= 0
        || findScalarSource(sql, 0, sql.length()) >= 0) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    result.reset();
    int start = query.blockCount();
    int derived = findDerivedSource(sql, 0, sql.length());
    if (derived >= 0) {
      StatusCode status = parseDerivedBlocks(sql, 0, sql.length(), query);
      return status.isOk() ? result.copyBlockFrom(query.block(start)) : status;
    }
    source.set(sql, 0, sql.length(), sql.length(), sql.length());
    StatusCode status = statements.parseQueryBlock(source, result);
    if (!status.isOk()) return status;
    SqlCommand block = query.nextBlock();
    return block == null ? StatusCode.QUERY_TOO_COMPLEX : block.copyBlockFrom(result);
  }

  boolean hasNestedTopology(CharSequence sql) {
    int start = skipExplainPrefix(sql);
    return start < 0
        || findDerivedSource(sql, start, sql.length()) >= 0
        || findExistenceSource(sql, start, sql.length()) >= 0
        || findMembershipSource(sql, start, sql.length()) >= 0
        || findScalarSource(sql, start, sql.length()) >= 0;
  }

  private static int skipExplainPrefix(CharSequence sql) {
    int start = skipSpaces(sql, 0);
    if (!matchesKeyword(sql, start, sql.length(), "EXPLAIN")) {
      return start;
    }
    start = skipSpaces(sql, start + 7);
    if (matchesKeyword(sql, start, sql.length(), "ANALYZE")) {
      start = skipSpaces(sql, start + 7);
    }
    return start < sql.length() ? start : -1;
  }

  private static int skipExplainPrefix(CharSequence sql, SqlQuery query) {
    int start = skipSpaces(sql, 0);
    if (!matchesKeyword(sql, start, sql.length(), "EXPLAIN")) {
      return start;
    }
    start = skipSpaces(sql, start + 7);
    boolean analyze = matchesKeyword(sql, start, sql.length(), "ANALYZE");
    if (analyze) {
      start = skipSpaces(sql, start + 7);
    }
    if (start >= sql.length()) {
      return -1;
    }
    query.setExplain(analyze);
    return start;
  }

  private StatusCode parseExistencePredicate(
      CharSequence sql, int start, int open, SqlQuery query, SqlCommand result) {
    StatusCode status = matchingCloseParenthesis(sql, open, sql.length()) < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : parseExistenceBlocks(sql, start, sql.length(), query);
    return status.isOk()
        ? query.compileExistencePredicate(result, query.existenceNegated()) : status;
  }

  private StatusCode parseExistenceBlocks(
      CharSequence sql, int start, int end, SqlQuery query) {
    int open = findExistenceSource(sql, start, end);
    int close = open < 0 ? -1 : matchingCloseParenthesis(sql, open, end);
    int whereStart = existenceWhereStart;
    boolean negated = existenceNegated;
    if (open < 0 || close < 0 || whereStart < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int parentIndex = query.blockCount();
    SqlCommand parent = query.nextBlock();
    if (parent == null) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    source.set(sql, start, whereStart, close + 1, end);
    StatusCode status = statements.parseQueryBlock(source, parent);
    if (status.isOk()) {
      query.setExistencePredicate(parentIndex, negated);
      status = parseNestedBlocks(sql, open + 1, close, query);
    }
    return status;
  }

  private StatusCode parseScalarPredicate(
      CharSequence sql, int start, int open, SqlQuery query, SqlCommand result) {
    StatusCode status = matchingCloseParenthesis(sql, open, sql.length()) < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : parseScalarBlocks(sql, start, sql.length(), query);
    return status.isOk()
        ? query.compileScalarPredicate(result, query.scalarPredicate()) : status;
  }

  private StatusCode parseScalarBlocks(
      CharSequence sql, int start, int end, SqlQuery query) {
    int open = findScalarSource(sql, start, end);
    int close = open < 0 ? -1 : matchingCloseParenthesis(sql, open, end);
    if (open < 0 || close < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int parentIndex = query.blockCount();
    SqlCommand parent = query.nextBlock();
    if (parent == null) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    scalarSource.set(sql, start, open, close + 1, end, false);
    StatusCode status = statements.parseSyntheticQueryBlock(
        scalarSource, scalarSource.replacementOffset(), parent);
    int predicate = statements.syntheticPredicateIndex();
    if (status.isOk() && predicate < 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      query.setScalarPredicate(parentIndex, predicate);
      status = parseNestedBlocks(sql, open + 1, close, query);
    }
    return status;
  }

  private StatusCode parseMembershipPredicate(
      CharSequence sql, int start, int open, SqlQuery query, SqlCommand result) {
    StatusCode status = matchingCloseParenthesis(sql, open, sql.length()) < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : parseMembershipBlocks(sql, start, sql.length(), query);
    return status.isOk()
        ? query.compileMembershipPredicate(
            result, query.membershipPredicate(), query.membershipNegated())
        : status;
  }

  private StatusCode parseMembershipBlocks(
      CharSequence sql, int start, int end, SqlQuery query) {
    int open = findMembershipSource(sql, start, end);
    int close = open < 0 ? -1 : matchingCloseParenthesis(sql, open, end);
    int operatorStart = membershipOperatorStart;
    boolean negated = membershipNegated;
    if (open < 0 || close < 0 || operatorStart < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int parentIndex = query.blockCount();
    SqlCommand parent = query.nextBlock();
    if (parent == null) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    scalarSource.set(sql, start, operatorStart, close + 1, end, true);
    StatusCode status = statements.parseSyntheticQueryBlock(
        scalarSource, scalarSource.replacementOffset(), parent);
    int predicate = statements.syntheticPredicateIndex();
    if (status.isOk() && predicate < 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      query.setMembershipPredicate(parentIndex, predicate, negated);
      status = parseNestedBlocks(sql, open + 1, close, query);
    }
    return status;
  }

  private StatusCode parseNestedBlocks(
      CharSequence sql, int start, int end, SqlQuery query) {
    if (findExistenceSource(sql, start, end) >= 0) {
      return parseExistenceBlocks(sql, start, end, query);
    }
    if (findMembershipSource(sql, start, end) >= 0) {
      return parseMembershipBlocks(sql, start, end, query);
    }
    if (findScalarSource(sql, start, end) >= 0) {
      return parseScalarBlocks(sql, start, end, query);
    }
    SqlCommand nested = query.nextBlock();
    if (nested == null) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    source.set(sql, start, end, end, end);
    return statements.parseQueryBlock(source, nested);
  }

  private StatusCode parseDerivedBlocks(
      CharSequence sql, int start, int end, SqlQuery query) {
    SqlCommand block = query.nextBlock();
    if (block == null) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    int open = findDerivedSource(sql, start, end);
    if (open < 0) {
      source.set(sql, start, end, end, end);
      return statements.parseQueryBlock(source, block);
    }
    int close = matchingCloseParenthesis(sql, open, end);
    if (close < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    source.set(sql, start, open, close + 1, end);
    StatusCode status = statements.parseQueryBlock(source, block);
    return status.isOk()
        ? parseDerivedBlocks(sql, open + 1, close, query) : status;
  }

  private static int findDerivedSource(CharSequence sql, int start, int end) {
    int from = findTopLevelKeyword(sql, start, end, "FROM");
    int sourceStart = from < 0 ? -1 : skipSpaces(sql, from + 4);
    return sourceStart >= 0 && sourceStart < end && sql.charAt(sourceStart) == '('
        ? sourceStart : -1;
  }

  private static int matchingCloseParenthesis(CharSequence sql, int open, int end) {
    int depth = 0;
    for (int index = open; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '(') {
        depth++;
      } else if (character == ')' && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  private static int findScalarSource(CharSequence sql, int start, int end) {
    int depth = 0;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '(') {
        depth++;
      } else if (character == ')') {
        if (depth <= 0) return -1;
        depth--;
      } else if (depth == 0 && character == '=') {
        int open = skipSpaces(sql, index + 1);
        if (isSelectOpening(sql, open, end)) return open;
      }
    }
    return -1;
  }

  private int findMembershipSource(CharSequence sql, int start, int end) {
    membershipOperatorStart = -1;
    membershipNegated = false;
    int search = start;
    while (search < end) {
      int in = findTopLevelKeyword(sql, search, end, "IN");
      if (in < 0) return -1;
      int open = skipSpaces(sql, in + 2);
      if (isSelectOpening(sql, open, end)) {
        return recordMembership(sql, start, in, open);
      }
      search = in + 2;
    }
    return -1;
  }

  private int findExistenceSource(CharSequence sql, int start, int end) {
    existenceWhereStart = -1;
    existenceNegated = false;
    int where = findTopLevelKeyword(sql, start, end, "WHERE");
    if (where < 0) return -1;
    int predicate = skipSpaces(sql, where + 5);
    if (matchesKeyword(sql, predicate, end, "NOT")) {
      existenceNegated = true;
      predicate = skipSpaces(sql, predicate + 3);
    }
    if (!matchesKeyword(sql, predicate, end, "EXISTS")) return -1;
    int open = skipSpaces(sql, predicate + 6);
    if (!isSelectOpening(sql, open, end)) return -1;
    existenceWhereStart = where;
    return open;
  }

  private static int findTopLevelKeyword(
      CharSequence sql, int start, int end, String keyword) {
    int depth = 0;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '(') depth++;
      else if (character == ')') {
        if (depth <= 0) return -1;
        depth--;
      } else if (depth == 0 && matchesKeyword(sql, index, end, keyword)) {
        return index;
      }
    }
    return -1;
  }

  private static boolean isSelectOpening(CharSequence sql, int open, int end) {
    return open < end
        && sql.charAt(open) == '('
        && matchesKeyword(sql, skipSpaces(sql, open + 1), end, "SELECT");
  }

  private int recordMembership(CharSequence sql, int start, int in, int open) {
    int priorEnd = in;
    while (priorEnd > start && Character.isWhitespace(sql.charAt(priorEnd - 1))) {
      priorEnd--;
    }
    int priorStart = priorEnd - 3;
    membershipNegated = priorStart >= start
        && matchesKeyword(sql, priorStart, priorEnd, "NOT");
    membershipOperatorStart = membershipNegated ? priorStart : in;
    return open;
  }

  private static boolean matchesKeyword(
      CharSequence sql, int start, int end, String keyword) {
    if (start > 0 && identifierPart(sql.charAt(start - 1))
        || end - start < keyword.length()) {
      return false;
    }
    for (int index = 0; index < keyword.length(); index++) {
      if (upper(sql.charAt(start + index)) != keyword.charAt(index)) {
        return false;
      }
    }
    int keywordEnd = start + keyword.length();
    return keywordEnd >= end || !identifierPart(sql.charAt(keywordEnd));
  }

  private static int skipSpaces(CharSequence sql, int start) {
    int index = start;
    while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
      index++;
    }
    return index;
  }

  private static char upper(char character) {
    return character >= 'a' && character <= 'z'
        ? (char) (character - ('a' - 'A')) : character;
  }

  private static boolean identifierPart(char character) {
    return character >= 'a' && character <= 'z'
        || character >= 'A' && character <= 'Z'
        || character >= '0' && character <= '9'
        || character == '_';
  }

  private static class SourceView implements CharSequence {
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

    @Override
    public int length() {
      return firstLength + secondLength;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length()) throw new IndexOutOfBoundsException(index);
      return index < firstLength
          ? source.charAt(firstStart + index)
          : source.charAt(secondStart + index - firstLength);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class ScalarSourceView implements CharSequence {
    private CharSequence source;
    private int firstStart;
    private int firstLength;
    private int secondStart;
    private int secondLength;
    private boolean equality;

    void set(
        CharSequence text,
        int firstFrom,
        int firstTo,
        int secondFrom,
        int secondTo,
        boolean includeEquality) {
      source = text;
      firstStart = firstFrom;
      firstLength = firstTo - firstFrom;
      secondStart = secondFrom;
      secondLength = secondTo - secondFrom;
      equality = includeEquality;
    }

    @Override
    public int length() {
      return firstLength + (equality ? 2 : 1) + secondLength;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length()) throw new IndexOutOfBoundsException(index);
      if (index < firstLength) return source.charAt(firstStart + index);
      if (equality && index == firstLength) return '=';
      int replacement = replacementOffset();
      return index == replacement
          ? '0' : source.charAt(secondStart + index - replacement - 1);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }

    int replacementOffset() {
      return firstLength + (equality ? 1 : 0);
    }
  }
}
