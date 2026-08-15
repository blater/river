package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;
import java.nio.ByteBuffer;

/** Compares one evaluated HAVING LHS with its typed literal shape. */
final class SqlHavingPredicateComparator {
  static final int UNKNOWN = -1;
  private final SqlExpressionEvaluator comparisons;
  private final SqlRowProjectionEvaluator programs;
  private final ResultText resultText;
  private byte[] text;
  private ByteBuffer textBuffer;
  private StatusCode status = StatusCode.OK;
  private int truth;

  SqlHavingPredicateComparator(
      SqlExpressionEvaluator expressionEvaluator,
      SqlRowProjectionEvaluator programEvaluator) {
    comparisons = expressionEvaluator;
    programs = programEvaluator;
    resultText = new ResultText(programEvaluator);
  }

  void evaluate(
      SqlCommand command,
      SqlBoundHavingPrograms having,
      SqlAggregateAccumulatorSet aggregates,
      byte[] groupText,
      int groupTextLength,
      int predicate) {
    status = StatusCode.OK;
    boolean nullValue = programs.resultNull();
    if (command.havingNullPredicate(predicate)) {
      truth = command.havingNullNegated(predicate) != nullValue ? 1 : 0;
      return;
    }
    if (nullValue) {
      truth = UNKNOWN;
      return;
    }
    int descriptor = programs.resultDescriptor();
    truth = SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? text(command, having, aggregates, groupText, groupTextLength, predicate)
        : primitive(command, predicate, programs.resultValue(), descriptor);
  }

  StatusCode status() { return status; }
  int truth() { return truth; }

  void reset() {
    if (text == null) return;
    for (int index = 0; index < text.length; index++) text[index] = 0;
    textBuffer.clear();
  }

  private int primitive(
      SqlCommand command, int predicate, long value, int descriptor) {
    SqlComparison comparison = command.havingComparison(predicate);
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      if (command.havingValueNull(predicate)
          || command.havingUpperNull(predicate)) return UNKNOWN;
      return matches(command, predicate, value, descriptor,
              SqlComparison.GREATER_OR_EQUAL,
              command.havingLower(predicate), command.havingValueDescriptor(predicate))
          && matches(command, predicate, value, descriptor,
              SqlComparison.LESS_OR_EQUAL,
              command.havingUpper(predicate), command.havingUpperDescriptor(predicate))
          ? 1 : 0;
    }
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      return membership(command, predicate, value, descriptor);
    }
    if (command.havingValueNull(predicate)) return UNKNOWN;
    return matches(
        command, predicate, value, descriptor, comparison,
        command.havingValue(predicate), command.havingValueDescriptor(predicate))
        ? 1 : 0;
  }

  private boolean matches(
      SqlCommand command,
      int predicate,
      long value,
      int descriptor,
      SqlComparison comparison,
      long expected,
      int expectedDescriptor) {
    return comparisons.matchesComparison(
        value, descriptor, comparison, expected, expectedDescriptor);
  }

  private int membership(
      SqlCommand command, int predicate, long value, int descriptor) {
    boolean found = false;
    for (int member = 0; member < command.havingMemberCount(predicate); member++) {
      if (matches(
          command,
          predicate,
          value,
          descriptor,
          SqlComparison.EQUAL,
          command.havingMember(predicate, member),
          command.havingValueDescriptor(predicate))) {
        found = true;
        break;
      }
    }
    return membershipTruth(command, predicate, found);
  }

  private int text(
      SqlCommand command,
      SqlBoundHavingPrograms having,
      SqlAggregateAccumulatorSet aggregates,
      byte[] groupText,
      int groupTextLength,
      int predicate) {
    int length = resultText(
        having, aggregates, groupText, groupTextLength, predicate);
    if (length < 0) {
      status = StatusCode.CORRUPTION;
      return UNKNOWN;
    }
    SqlComparison comparison = command.havingComparison(predicate);
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return textRange(command, predicate, length);
    }
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      return textMembership(command, predicate, length);
    }
    if (command.havingValueNull(predicate)) return UNKNOWN;
    int compared = compare(command, command.havingValue(predicate), length);
    return comparisonTruth(comparison, compared);
  }

  private int textRange(SqlCommand command, int predicate, int length) {
    if (command.havingValueNull(predicate)
        || command.havingUpperNull(predicate)) return UNKNOWN;
    return compare(command, command.havingLower(predicate), length) >= 0
            && compare(command, command.havingUpper(predicate), length) <= 0
        ? 1 : 0;
  }

  private int textMembership(SqlCommand command, int predicate, int length) {
    boolean found = false;
    for (int member = 0; member < command.havingMemberCount(predicate); member++) {
      if (compare(command, command.havingMember(predicate, member), length) == 0) {
        found = true;
        break;
      }
    }
    return membershipTruth(command, predicate, found);
  }

  private int resultText(
      SqlBoundHavingPrograms having,
      SqlAggregateAccumulatorSet aggregates,
      byte[] groupText,
      int groupTextLength,
      int predicate) {
    ensureText();
    int operator = having.nodeCount(predicate) == 1
        ? having.operator(predicate, 0) : 0;
    if (operator == SqlScalarExpression.AGGREGATE_VALUE) {
      int invocation = (int) having.operand(predicate, 0);
      int length = aggregates.textLength(invocation);
      byte[] source = aggregates.text();
      if (source == null) return length == 0 ? 0 : -1;
      System.arraycopy(source, aggregates.textOffset(invocation), text, 0, length);
      return length;
    }
    if (operator == SqlScalarExpression.GROUP_VALUE) {
      if (groupText == null || groupTextLength < 0
          || groupTextLength > text.length) return -1;
      System.arraycopy(groupText, 0, text, 0, groupTextLength);
      return groupTextLength;
    }
    textBuffer.clear();
    return Utf8Text.encode(resultText, Utf8Text.MAXIMUM_SCALARS, textBuffer);
  }

  private int compare(SqlCommand command, long handle, int actualLength) {
    int expectedLength = command.textByteLength(handle);
    if (expectedLength < 0) return Integer.MIN_VALUE;
    int common = Math.min(actualLength, expectedLength);
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(
          Byte.toUnsignedInt(text[index]),
          Byte.toUnsignedInt(command.textByteAt(handle, index)));
      if (compared != 0) return compared;
    }
    return Integer.compare(actualLength, expectedLength);
  }

  private void ensureText() {
    if (text == null) {
      text = new byte[Utf8Text.MAXIMUM_BYTES];
      textBuffer = ByteBuffer.wrap(text);
    }
  }

  private static int membershipTruth(
      SqlCommand command, int predicate, boolean found) {
    boolean negated = command.havingComparison(predicate) == SqlComparison.NOT_IN;
    if (found) return negated ? 0 : 1;
    if (command.havingMembershipHasNull(predicate)) return UNKNOWN;
    return negated ? 1 : 0;
  }

  private static int comparisonTruth(SqlComparison comparison, int compared) {
    return switch (comparison) {
      case EQUAL -> compared == 0 ? 1 : 0;
      case NOT_EQUAL -> compared != 0 ? 1 : 0;
      case LESS_THAN -> compared < 0 ? 1 : 0;
      case LESS_OR_EQUAL -> compared <= 0 ? 1 : 0;
      case GREATER_THAN -> compared > 0 ? 1 : 0;
      case GREATER_OR_EQUAL -> compared >= 0 ? 1 : 0;
      case HALF_OPEN_RANGE, IN, NOT_IN -> 0;
    };
  }

  private static final class ResultText implements CharSequence {
    private final SqlRowProjectionEvaluator evaluator;

    ResultText(SqlRowProjectionEvaluator source) { evaluator = source; }
    @Override public int length() { return evaluator.resultTextLength(); }
    @Override public char charAt(int index) {
      return evaluator.resultTextCharacter(index);
    }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
