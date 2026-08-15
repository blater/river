package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import java.nio.ByteBuffer;

/** Evaluates one block-local predicate set over an owned virtual row. */
final class SqlBlockPredicateEvaluator {
  private final SqlExpressionEvaluator exact;
  private final SqlRowProjectionEvaluator computed;
  private final char[] literal = new char[510];
  private ByteBuffer utf8;

  SqlBlockPredicateEvaluator(
      SqlExpressionEvaluator exactEvaluator,
      SqlRowProjectionEvaluator computedEvaluator) {
    exact = exactEvaluator;
    computed = computedEvaluator;
  }

  StatusCode matches(
      SqlCommand command,
      SqlBlockSchema schema,
      SqlBlockRow row,
      BoundSqlStatement bound,
      Match result) {
    boolean conjunction = true;
    boolean disjunction = false;
    for (int predicate = 0; predicate < command.predicateCount(); predicate++) {
      if (command.predicateStartsDisjunction(predicate)) {
        disjunction |= conjunction;
        conjunction = true;
      }
      if (!conjunction) continue;
      StatusCode status = predicate(command, schema, row, bound, predicate, result);
      if (!status.isOk()) return status;
      conjunction = result.matched;
    }
    result.matched = disjunction || conjunction;
    return StatusCode.OK;
  }

  private StatusCode predicate(
      SqlCommand command,
      SqlBlockSchema schema,
      SqlBlockRow row,
      BoundSqlStatement bound,
      int predicate,
      Match result) {
    if (command.predicateExpression(predicate) != null) {
      StatusCode status = computed.evaluatePredicateBlock(row);
      if (!status.isOk()) return status;
      return compareComputed(command, predicate, result);
    }
    int column = bound.predicateColumns[predicate];
    if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    boolean nullValue = row.nullValue(column);
    if (command.isNullPredicate(predicate)) {
      result.matched = nullValue != command.isNullPredicateNegated(predicate);
      return StatusCode.OK;
    }
    if (nullValue) {
      result.matched = false;
      return StatusCode.OK;
    }
    if (schema.varchar(column)) {
      return compareText(command, row, column,
          bound.blockPredicateRightColumns[predicate], predicate, result);
    }
    long value = row.value(column);
    if (command.isColumnPredicate(predicate)) {
      int right = bound.blockPredicateRightColumns[predicate];
      result.matched = right >= 0 && !row.nullValue(right)
          && exact.matchesComparison(
              value, schema.descriptor(column), command.comparison(predicate),
              row.value(right), schema.descriptor(right));
    } else {
      result.matched = exact.matchesComparison(
          value, schema.descriptor(column), command, predicate);
    }
    return StatusCode.OK;
  }

  private StatusCode compareComputed(
      SqlCommand command, int predicate, Match result) {
    if (command.isNullPredicate(predicate)) {
      result.matched = computed.predicateNull()
          != command.isNullPredicateNegated(predicate);
      return StatusCode.OK;
    }
    if (computed.predicateNull()) {
      result.matched = false;
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.typeId(computed.predicateDescriptor())
        == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return compareGeneratedText(command, predicate, result);
    }
    result.matched = exact.matchesComparison(
        computed.predicateValue(), computed.predicateDescriptor(), command, predicate);
    return StatusCode.OK;
  }

  private StatusCode compareText(
      SqlCommand command,
      SqlBlockRow row,
      int column,
      int rightColumn,
      int predicate,
      Match result) {
    if (command.isColumnPredicate(predicate)) {
      if (rightColumn < 0 || row.nullValue(rightColumn)) {
        result.matched = false;
      } else {
        result.matched = comparison(
            compare(row, column, row, rightColumn), command.comparison(predicate));
      }
      return StatusCode.OK;
    }
    SqlComparison comparison = command.comparison(predicate);
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
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
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      int lower = load(command, command.predicateLowerInclusive(predicate));
      if (lower < 0) return StatusCode.CORRUPTION;
      int low = compare(row, column, literal, lower);
      int upper = load(command, command.predicateUpperExclusive(predicate));
      if (upper < 0) return StatusCode.CORRUPTION;
      result.matched = low >= 0 && compare(row, column, literal, upper) < 0;
      return StatusCode.OK;
    }
    int length = load(command, command.predicateValue(predicate));
    if (length < 0) return StatusCode.CORRUPTION;
    result.matched = comparison(
        compare(row, column, literal, length), comparison);
    return StatusCode.OK;
  }

  private StatusCode compareGeneratedText(
      SqlCommand command, int predicate, Match result) {
    int length = load(command, command.predicateValue(predicate));
    if (length < 0) return StatusCode.CORRUPTION;
    int compared = compareGenerated(literal, length);
    result.matched = comparison(compared, command.comparison(predicate));
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
    return Integer.compare(leftIndex == leftLength ? 0 : 1, rightIndex == rightLength ? 0 : 1);
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
    return Integer.compare(leftIndex == leftLength ? 0 : 1, rightIndex == rightLength ? 0 : 1);
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
    return Integer.compare(leftIndex == leftLength ? 0 : 1, rightIndex == rightLength ? 0 : 1);
  }

  private static int codePoint(
      SqlBlockRow row, int column, int index, int length) {
    char first = row.textCharacter(column, index);
    return Character.isHighSurrogate(first) && index + 1 < length
        ? Character.toCodePoint(first, row.textCharacter(column, index + 1))
        : first;
  }

  private static boolean comparison(int compared, SqlComparison comparison) {
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
      for (int index = 0; index < utf8.capacity(); index++) utf8.put(index, (byte) 0);
      utf8.clear();
    }
    for (int index = 0; index < literal.length; index++) literal[index] = 0;
  }

  static final class Match { boolean matched; }
}
