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
    return SqlNestedSubqueryScanner.firstSelectOpening(sql, from, to) >= 0;
  }

  StatusCode scan(CharSequence sql, int from, int to, SqlQuery query, int block, int[] offsets) {
    firstEdge = query.edgeCount();
    count = 0;
    int where = SqlNestedSubqueryScanner.topLevelKeyword(sql, from, to, "WHERE");
    int opening = SqlNestedSubqueryScanner.firstSelectOpening(sql, from, to);
    while (opening >= 0) {
      if (where < 0 || opening < where) return StatusCode.FEATURE_NOT_SUPPORTED;
      int close = SqlNestedSubqueryScanner.matchingClose(sql, opening, to);
      if (close < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      int kind = SqlNestedSubqueryScanner.kind(sql, from, opening, close, to);
      if (count >= SqlBooleanPredicateProgram.MAXIMUM_LEAVES) return StatusCode.RESOURCE_EXHAUSTED;
      int edge = query.addSubqueryEdge(block, kind);
      if (edge < 0) return StatusCode.QUERY_TOO_COMPLEX;
      int replacement = kind == SqlQuery.SUBQUERY_EXISTS
          ? SqlNestedSubqueryScanner.priorKeywordStart(sql, from, opening, "EXISTS") : opening;
      if (replacement < from || count > 0 && replacement <= closes[edge - 1]) return StatusCode.INVALID_EXTERNAL_INPUT;
      starts[edge] = replacement; opens[edge] = opening; closes[edge] = close; kinds[edge] = kind;
      count++;
      opening = SqlNestedSubqueryScanner.firstSelectOpening(sql, close + 1, to);
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

  @Override public char charAt(int index) {
    if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
    int output = 0, original = start;
    for (int item = 0; item < count; item++) {
      int edge = firstEdge + item, plain = starts[edge] - original;
      if (index < output + plain) return source.charAt(original + index - output);
      output += plain;
      int replacement = replacementLength(kinds[edge]);
      if (index < output + replacement) return replacementCharacter(kinds[edge], index - output);
      output += replacement;
      original = closes[edge] + 1;
    }
    return source.charAt(original + index - output);
  }

  @Override public int parameterOrdinal(int offset) {
    int original = originalOffset(offset);
    return original < 0 || source.charAt(original) != '?' ? -1
        : SqlParameterOrdinalSource.ordinal(source, original);
  }

  @Override public CharSequence subSequence(int from, int to) {
    throw new UnsupportedOperationException();
  }

  private void set(CharSequence sql, int from, int to, int[] offsets) {
    source = sql; start = from; length = to - from;
    int removed = 0;
    for (int index = 0; index < count; index++) {
      int edge = firstEdge + index, replacement = replacementLength(kinds[edge]);
      offsets[index] = starts[edge] - start - removed + (kinds[edge] == SqlQuery.SUBQUERY_MEMBERSHIP ? 1 : 0);
      removed += closes[edge] + 1 - starts[edge] - replacement;
    }
    length -= removed;
  }

  private int originalOffset(int index) {
    if (index < 0 || index >= length) return -1;
    int output = 0, original = start;
    for (int item = 0; item < count; item++) {
      int edge = firstEdge + item, plain = starts[edge] - original;
      if (index < output + plain) return original + index - output;
      output += plain;
      int replacement = replacementLength(kinds[edge]);
      if (index < output + replacement) return -1;
      output += replacement; original = closes[edge] + 1;
    }
    return original + index - output;
  }

  private static int replacementLength(int kind) {
    return kind == SqlQuery.SUBQUERY_EXISTS ? 4 : kind == SqlQuery.SUBQUERY_MEMBERSHIP ? 3 : 1;
  }

  private static char replacementCharacter(int kind, int offset) {
    if (kind == SqlQuery.SUBQUERY_EXISTS) return "TRUE".charAt(offset);
    if (kind == SqlQuery.SUBQUERY_MEMBERSHIP) return offset == 0 ? '(' : offset == 1 ? '0' : ')';
    return '0';
  }
}
