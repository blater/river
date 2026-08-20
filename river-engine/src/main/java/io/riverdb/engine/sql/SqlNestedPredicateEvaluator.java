package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlComparison;
import io.riverdb.storage.heap.HeapRowResult;

/** Evaluates literal and column predicates used by nested query blocks. */
final class SqlNestedPredicateEvaluator {
  private final SqlExpressionEvaluator expressions;
  private StatusCode status = StatusCode.OK;

  SqlNestedPredicateEvaluator(SqlExpressionEvaluator evaluator) {
    expressions = evaluator;
  }

  void reset() { status = StatusCode.OK; }
  StatusCode status() { return status; }

  boolean matchesLiteral(
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      int column,
      SqlNestedPredicatePlan predicateSource,
      int predicate) {
    if (!status.isOk() || predicateSource.isValueNull(predicate)) return false;
    if (predicateSource.isBetween(predicate)
        && (predicateSource.isLowerNull(predicate)
            || predicateSource.isUpperNull(predicate))) return false;
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
          index < predicateSource.memberCount(predicate);
          index++) {
        if (!predicateSource.memberNull(predicate, index)
            && compareText(
            source,
            definition,
            column,
            predicateSource,
            predicateSource.memberValue(predicate, index)) == 0) {
          equal = true;
          break;
        }
      }
      return comparison == SqlComparison.IN
          ? equal : !equal && !predicateSource.hasNullMember(predicate);
    }
    if (predicateSource.isBetween(predicate)) {
      return compareText(
              source,
              definition,
              column,
              predicateSource,
              predicateSource.lowerInclusive(predicate)) >= 0
          && compareText(
              source,
              definition,
              column,
              predicateSource,
              predicateSource.upperExclusive(predicate)) <= 0;
    }
    int compared = compareText(
        source, definition, column, predicateSource, predicateSource.value(predicate));
    if (!status.isOk()) return false;
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
    if (!status.isOk()) return false;
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
    if (!validTextHandle(
            left, leftDefinition.fixedRowBytes(), leftOffset, leftLength)
        || !validTextHandle(
            right, rightDefinition.fixedRowBytes(), rightOffset, rightLength)) {
      status = StatusCode.CORRUPTION;
      return false;
    }
    if (leftLength != rightLength) {
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
      SqlNestedPredicatePlan source,
      int predicate) {
    if (source.isBetween(predicate)) {
      return expressions.compareExact(
              actual,
              actualDescriptor,
              source.lowerInclusive(predicate),
              source.lowerDescriptor(predicate)) >= 0
          && expressions.compareExact(
              actual,
              actualDescriptor,
              source.upperExclusive(predicate),
              source.upperDescriptor(predicate)) <= 0;
    }
    int expectedDescriptor = source.typeDescriptor(predicate);
    SqlComparison comparison = source.comparison(predicate);
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return expressions.compareExact(
              actual,
              actualDescriptor,
              source.lowerInclusive(predicate),
              expectedDescriptor) >= 0
          && expressions.compareExact(
              actual,
              actualDescriptor,
              source.upperExclusive(predicate),
              expectedDescriptor) < 0;
    }
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = false;
      for (int index = 0; index < source.memberCount(predicate); index++) {
        if (!source.memberNull(predicate, index)
            && expressions.compareExact(
            actual,
            actualDescriptor,
            source.memberValue(predicate, index),
            source.memberDescriptor(predicate, index)) == 0) {
          equal = true;
          break;
        }
      }
      return comparison == SqlComparison.IN
          ? equal : !equal && !source.hasNullMember(predicate);
    }
    return expressions.matchesComparison(
        actual,
        actualDescriptor,
        comparison,
        source.value(predicate),
        expectedDescriptor);
  }

  private int compareText(
      HeapRowResult actual,
      TableDefinition definition,
      int column,
      SqlNestedPredicatePlan expected,
      long expectedHandle) {
    long actualHandle = actual.getLong((column - 1) * Long.BYTES);
    int actualOffset = (int) (actualHandle >>> 32);
    int actualLength = (int) actualHandle;
    int expectedLength = expected.textByteLength(expectedHandle);
    if (!validTextHandle(
            actual, definition.fixedRowBytes(), actualOffset, actualLength)
        || expectedLength < 0) {
      status = StatusCode.CORRUPTION;
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
      HeapRowResult source, int minimumOffset, int offset, int length) {
    return offset >= minimumOffset && length >= 0
        && offset <= source.length() - length;
  }

  boolean matchesTruth(
      SqlNestedPredicatePlan source, int predicate, boolean nullValue, long value) {
    SqlComparison comparison = source.comparison(predicate);
    boolean matched = comparison == null
        ? nullValue : !nullValue
            && (comparison == SqlComparison.EQUAL) == (value != 0);
    return source.isNullTestNegated(predicate) ? !matched : matched;
  }
}
