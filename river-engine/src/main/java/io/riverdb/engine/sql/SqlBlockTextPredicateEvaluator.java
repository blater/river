package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import java.nio.ByteBuffer;

/** Owns bounded UTF-8 literal scratch and scalar-order text comparison. */
final class SqlBlockTextPredicateEvaluator {
  private final SqlRowProjectionEvaluator computed;
  private final char[] literal = new char[510];
  private ByteBuffer utf8;

  SqlBlockTextPredicateEvaluator(SqlRowProjectionEvaluator computedEvaluator) {
    computed = computedEvaluator;
  }

  StatusCode compare(
      SqlCommand command,
      SqlBlockRow row,
      int column,
      int rightColumn,
      int predicate,
      SqlBlockPredicateEvaluator.Match result) {
    if (command.isColumnPredicate(predicate)) {
      result.matched = rightColumn >= 0 && !row.nullValue(rightColumn)
          && matches(compare(row, column, row, rightColumn), command.comparison(predicate));
      return StatusCode.OK;
    }
    SqlComparison comparison = command.comparison(predicate);
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      return membership(command, row, column, predicate, comparison, result);
    }
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return range(command, row, column, predicate, result);
    }
    int length = load(command, command.predicateValue(predicate));
    if (length < 0) return StatusCode.CORRUPTION;
    result.matched = matches(compare(row, column, literal, length), comparison);
    return StatusCode.OK;
  }

  StatusCode compareGenerated(
      SqlCommand command,
      int predicate,
      SqlBlockPredicateEvaluator.Match result) {
    int length = load(command, command.predicateValue(predicate));
    if (length < 0) return StatusCode.CORRUPTION;
    result.matched = matches(compareGenerated(literal, length), command.comparison(predicate));
    return StatusCode.OK;
  }

  private StatusCode membership(
      SqlCommand command,
      SqlBlockRow row,
      int column,
      int predicate,
      SqlComparison comparison,
      SqlBlockPredicateEvaluator.Match result) {
    boolean equal = false;
    for (int member = 0; member < command.literalMembershipCount(predicate); member++) {
      int loaded = load(command, command.literalMembershipValue(predicate, member));
      if (loaded < 0) return StatusCode.CORRUPTION;
      if (compare(row, column, literal, loaded) == 0) equal = true;
    }
    result.matched = comparison == SqlComparison.IN ? equal
        : !equal && !command.literalMembershipHasNull(predicate);
    return StatusCode.OK;
  }

  private StatusCode range(
      SqlCommand command,
      SqlBlockRow row,
      int column,
      int predicate,
      SqlBlockPredicateEvaluator.Match result) {
    int lower = load(command, command.predicateLowerInclusive(predicate));
    if (lower < 0) return StatusCode.CORRUPTION;
    int low = compare(row, column, literal, lower);
    int upper = load(command, command.predicateUpperExclusive(predicate));
    if (upper < 0) return StatusCode.CORRUPTION;
    result.matched = low >= 0 && compare(row, column, literal, upper) < 0;
    return StatusCode.OK;
  }

  private int load(SqlCommand command, long handle) {
    int bytes = command.textByteLength(handle);
    if (bytes < 0 || bytes > Utf8Text.MAXIMUM_BYTES) return -1;
    if (utf8 == null) utf8 = ByteBuffer.allocateDirect(Utf8Text.MAXIMUM_BYTES);
    utf8.clear();
    if (command.copyText(handle, utf8) != bytes) return -1;
    utf8.flip();
    return Utf8Text.decode(utf8, 0, bytes, literal, 0);
  }

  private static int compare(
      SqlBlockRow left, int leftColumn, SqlBlockRow right, int rightColumn) {
    int leftLength = left.textLength(leftColumn);
    int rightLength = right.textLength(rightColumn);
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < leftLength && rightIndex < rightLength) {
      int leftScalar = codePoint(left, leftColumn, leftIndex, leftLength);
      int rightScalar = codePoint(right, rightColumn, rightIndex, rightLength);
      int compared = Integer.compare(leftScalar, rightScalar);
      if (compared != 0) return compared;
      leftIndex += Character.charCount(leftScalar);
      rightIndex += Character.charCount(rightScalar);
    }
    return remaining(leftIndex, leftLength, rightIndex, rightLength);
  }

  private static int compare(
      SqlBlockRow left, int column, char[] right, int rightLength) {
    int leftLength = left.textLength(column);
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < leftLength && rightIndex < rightLength) {
      int leftScalar = codePoint(left, column, leftIndex, leftLength);
      int rightScalar = Character.codePointAt(right, rightIndex, rightLength);
      int compared = Integer.compare(leftScalar, rightScalar);
      if (compared != 0) return compared;
      leftIndex += Character.charCount(leftScalar);
      rightIndex += Character.charCount(rightScalar);
    }
    return remaining(leftIndex, leftLength, rightIndex, rightLength);
  }

  private int compareGenerated(char[] right, int rightLength) {
    int leftLength = computed.predicateTextLength();
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < leftLength && rightIndex < rightLength) {
      char first = computed.predicateTextCharacter(leftIndex);
      int leftScalar = Character.isHighSurrogate(first) && leftIndex + 1 < leftLength
          ? Character.toCodePoint(first, computed.predicateTextCharacter(leftIndex + 1))
          : first;
      int rightScalar = Character.codePointAt(right, rightIndex, rightLength);
      int compared = Integer.compare(leftScalar, rightScalar);
      if (compared != 0) return compared;
      leftIndex += Character.charCount(leftScalar);
      rightIndex += Character.charCount(rightScalar);
    }
    return remaining(leftIndex, leftLength, rightIndex, rightLength);
  }

  private static int remaining(
      int leftIndex, int leftLength, int rightIndex, int rightLength) {
    return Integer.compare(leftIndex == leftLength ? 0 : 1, rightIndex == rightLength ? 0 : 1);
  }

  private static int codePoint(
      SqlBlockRow row, int column, int index, int length) {
    char first = row.textCharacter(column, index);
    return Character.isHighSurrogate(first) && index + 1 < length
        ? Character.toCodePoint(first, row.textCharacter(column, index + 1))
        : first;
  }

  private static boolean matches(int compared, SqlComparison comparison) {
    return switch (comparison) {
      case EQUAL -> compared == 0;
      case NOT_EQUAL -> compared != 0;
      case LESS_THAN -> compared < 0;
      case LESS_OR_EQUAL -> compared <= 0;
      case GREATER_THAN -> compared > 0;
      case GREATER_OR_EQUAL -> compared >= 0;
      case HALF_OPEN_RANGE, IN, NOT_IN -> false;
    };
  }

  void reset() {
    if (utf8 != null) {
      utf8.clear();
      for (int index = 0; index < utf8.capacity(); index++) utf8.put(index, (byte) 0);
      utf8.clear();
    }
    for (int index = 0; index < literal.length; index++) literal[index] = 0;
  }
}
