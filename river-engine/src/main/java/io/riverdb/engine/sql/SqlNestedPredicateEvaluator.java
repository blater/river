package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlComparison;
import io.riverdb.storage.heap.HeapRowResult;

/** Evaluates literal and column predicates used by nested query blocks. */
final class SqlNestedPredicateEvaluator {
  private final SqlExpressionEvaluator expressions;

  SqlNestedPredicateEvaluator(SqlExpressionEvaluator evaluator) {
    expressions = evaluator;
  }

  boolean matchesLiteral(
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      int column,
      BoundSqlQuery.Block predicateSource,
      int predicate) {
    if (!definition.isVarchar(column)) {
      return matchesNumber(
          expressions.readColumn(primaryKey, source, column),
          definition.typeDescriptor(column),
          predicateSource,
          predicate);
    }
    SqlComparison comparison = predicateSource.comparison(predicate);
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = false;
      for (int index = 0;
          index < predicateSource.literalMembershipCount(predicate);
          index++) {
        if (compareText(
            source,
            column,
            predicateSource,
            predicateSource.literalMembershipValue(predicate, index)) == 0) {
          equal = true;
          break;
        }
      }
      return comparison == SqlComparison.IN
          ? equal : !equal && !predicateSource.literalMembershipHasNull(predicate);
    }
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return compareText(
              source,
              column,
              predicateSource,
              predicateSource.predicateLowerInclusive(predicate)) >= 0
          && compareText(
              source,
              column,
              predicateSource,
              predicateSource.predicateUpperExclusive(predicate)) < 0;
    }
    int compared = compareText(
        source, column, predicateSource, predicateSource.predicateValue(predicate));
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

  boolean equalColumns(
      long leftKey,
      HeapRowResult left,
      TableDefinition leftDefinition,
      int leftColumn,
      long rightKey,
      HeapRowResult right,
      TableDefinition rightDefinition,
      int rightColumn) {
    if (!leftDefinition.isVarchar(leftColumn)) {
      return expressions.compareExact(
          expressions.readColumn(leftKey, left, leftColumn),
          leftDefinition.typeDescriptor(leftColumn),
          expressions.readColumn(rightKey, right, rightColumn),
          rightDefinition.typeDescriptor(rightColumn)) == 0;
    }
    long leftHandle = expressions.readColumn(leftKey, left, leftColumn);
    long rightHandle = expressions.readColumn(rightKey, right, rightColumn);
    int leftOffset = (int) (leftHandle >>> 32);
    int leftLength = (int) leftHandle;
    int rightOffset = (int) (rightHandle >>> 32);
    int rightLength = (int) rightHandle;
    if (!validTextHandle(left, leftOffset, leftLength)
        || !validTextHandle(right, rightOffset, rightLength)
        || leftLength != rightLength) {
      return false;
    }
    for (int index = 0; index < leftLength; index++) {
      if (left.getByte(leftOffset + index) != right.getByte(rightOffset + index)) {
        return false;
      }
    }
    return true;
  }

  private boolean matchesNumber(
      long actual,
      int actualDescriptor,
      BoundSqlQuery.Block source,
      int predicate) {
    int expectedDescriptor = source.predicateTypeDescriptor(predicate);
    SqlComparison comparison = source.comparison(predicate);
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return expressions.compareExact(
              actual,
              actualDescriptor,
              source.predicateLowerInclusive(predicate),
              expectedDescriptor) >= 0
          && expressions.compareExact(
              actual,
              actualDescriptor,
              source.predicateUpperExclusive(predicate),
              expectedDescriptor) < 0;
    }
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = false;
      for (int index = 0; index < source.literalMembershipCount(predicate); index++) {
        if (expressions.compareExact(
            actual,
            actualDescriptor,
            source.literalMembershipValue(predicate, index),
            expectedDescriptor) == 0) {
          equal = true;
          break;
        }
      }
      return comparison == SqlComparison.IN
          ? equal : !equal && !source.literalMembershipHasNull(predicate);
    }
    return expressions.matchesComparison(
        actual,
        actualDescriptor,
        comparison,
        source.predicateValue(predicate),
        expectedDescriptor);
  }

  private static int compareText(
      HeapRowResult actual,
      int column,
      BoundSqlQuery.Block expected,
      long expectedHandle) {
    long actualHandle = actual.getLong((column - 1) * Long.BYTES);
    int actualOffset = (int) (actualHandle >>> 32);
    int actualLength = (int) actualHandle;
    int expectedLength = expected.textByteLength(expectedHandle);
    if (!validTextHandle(actual, actualOffset, actualLength) || expectedLength < 0) {
      return Integer.MIN_VALUE;
    }
    int common = Math.min(actualLength, expectedLength);
    for (int index = 0; index < common; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(actual.getByte(actualOffset + index)),
          Byte.toUnsignedInt(expected.textByteAt(expectedHandle, index)));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(actualLength, expectedLength);
  }

  private static boolean validTextHandle(
      HeapRowResult source, int offset, int length) {
    return offset >= 0 && length >= 0 && offset <= source.length() - length;
  }
}
