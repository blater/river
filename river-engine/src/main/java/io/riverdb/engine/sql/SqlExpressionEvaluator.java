package io.riverdb.engine.sql;

import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Allocation-free primitive SQL value, NULL, comparison, and text semantics. */
final class SqlExpressionEvaluator {
  long readColumn(long primaryKey, HeapRowResult source, int column) {
    return column == 0
        ? primaryKey : source.getLong((column - 1) * Long.BYTES);
  }

  boolean isNull(
      HeapRowResult source,
      TableDefinition definition,
      int column) {
    return column > 0
        && (source.getLong(definition.nullMaskOffset()) & 1L << column) != 0;
  }

  boolean matchesComparison(
      long actual,
      SqlCommand source,
      int predicate) {
    long expected = source.predicateValue(predicate);
    SqlComparison comparison = source.comparison(predicate);
    return switch (comparison) {
      case EQUAL -> actual == expected;
      case NOT_EQUAL -> actual != expected;
      case LESS_THAN -> actual < expected;
      case LESS_OR_EQUAL -> actual <= expected;
      case GREATER_THAN -> actual > expected;
      case GREATER_OR_EQUAL -> actual >= expected;
      case HALF_OPEN_RANGE ->
        actual >= source.predicateLowerInclusive(predicate)
            && actual < source.predicateUpperExclusive(predicate);
      case IN, NOT_IN -> matchesLiteralMembership(actual, source, predicate);
    };
  }

  boolean matchesComparison(
      long actual,
      int actualDescriptor,
      SqlCommand source,
      int predicate) {
    int expectedDescriptor = source.predicateTypeDescriptor(predicate);
    SqlComparison comparison = source.comparison(predicate);
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return compareExact(
              actual,
              actualDescriptor,
              source.predicateLowerInclusive(predicate),
              expectedDescriptor) >= 0
          && compareExact(
              actual,
              actualDescriptor,
              source.predicateUpperExclusive(predicate),
              expectedDescriptor) < 0;
    }
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = false;
      for (int index = 0; index < source.literalMembershipCount(predicate); index++) {
        if (compareExact(
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
    return matchesComparison(
        actual,
        actualDescriptor,
        comparison,
        source.predicateValue(predicate),
        expectedDescriptor);
  }

  boolean matchesTextComparison(
      HeapRowResult actual,
      TableDefinition definition,
      int column,
      SqlCommand expected,
      int predicate) {
    SqlComparison comparison = expected.comparison(predicate);
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = false;
      for (int index = 0; index < expected.literalMembershipCount(predicate); index++) {
        if (compareText(
            actual,
            definition,
            column,
            expected,
            expected.literalMembershipValue(predicate, index)) == 0) {
          equal = true;
          break;
        }
      }
      return comparison == SqlComparison.IN
          ? equal : !equal && !expected.literalMembershipHasNull(predicate);
    }
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return compareText(
              actual,
              definition,
              column,
              expected,
              expected.predicateLowerInclusive(predicate)) >= 0
          && compareText(
              actual,
              definition,
              column,
              expected,
              expected.predicateUpperExclusive(predicate)) < 0;
    }
    int result = compareText(
        actual, definition, column, expected, expected.predicateValue(predicate));
    return switch (comparison) {
      case EQUAL -> result == 0;
      case NOT_EQUAL -> result != 0;
      case LESS_THAN -> result < 0;
      case LESS_OR_EQUAL -> result <= 0;
      case GREATER_THAN -> result > 0;
      case GREATER_OR_EQUAL -> result >= 0;
      case HALF_OPEN_RANGE, IN, NOT_IN -> false;
    };
  }

  int compareText(
      HeapRowResult actual,
      TableDefinition definition,
      int column,
      SqlCommand expected,
      long expectedHandle) {
    long actualHandle = actual.getLong((column - 1) * Long.BYTES);
    int actualOffset = (int) (actualHandle >>> 32);
    int actualLength = (int) actualHandle;
    int expectedLength = expected.textByteLength(expectedHandle);
    if (actualOffset < 0 || actualLength < 0 || expectedLength < 0) {
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

  int compareText(
      HeapRowResult left,
      int leftOffset,
      int leftLength,
      ByteBuffer right,
      int rightLength) {
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(left.getByte(leftOffset + index)),
          Byte.toUnsignedInt(right.get(index)));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftLength, rightLength);
  }

  boolean matchesComparison(
      long actual,
      SqlComparison comparison,
      long expected) {
    return switch (comparison) {
      case EQUAL -> actual == expected;
      case NOT_EQUAL -> actual != expected;
      case LESS_THAN -> actual < expected;
      case LESS_OR_EQUAL -> actual <= expected;
      case GREATER_THAN -> actual > expected;
      case GREATER_OR_EQUAL -> actual >= expected;
      case HALF_OPEN_RANGE, IN, NOT_IN -> false;
    };
  }

  boolean matchesComparison(
      long actual,
      int actualDescriptor,
      SqlComparison comparison,
      long expected,
      int expectedDescriptor) {
    int compared = compareExact(actual, actualDescriptor, expected, expectedDescriptor);
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

  int compareExact(
      long left,
      int leftDescriptor,
      long right,
      int rightDescriptor) {
    return SqlTypeDescriptor.typeId(leftDescriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
            || SqlTypeDescriptor.typeId(rightDescriptor)
                == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? ExactDecimal.compare(left, leftDescriptor, right, rightDescriptor)
        : Long.compare(left, right);
  }

  boolean matchesLiteralMembership(
      long actual,
      SqlCommand source,
      int predicate) {
    boolean equal = false;
    int lower = 0;
    int upper = source.literalMembershipCount(predicate);
    while (lower < upper) {
      int middle = (lower + upper) >>> 1;
      long candidate = source.literalMembershipValue(predicate, middle);
      if (candidate < actual) {
        lower = middle + 1;
      } else if (candidate > actual) {
        upper = middle;
      } else {
        equal = true;
        break;
      }
    }
    return source.comparison(predicate) == SqlComparison.IN
        ? equal
        : !equal && !source.literalMembershipHasNull(predicate);
  }

  boolean arithmeticOverflow(
      long left,
      long right,
      long result,
      boolean subtract) {
    return subtract
        ? ((left ^ right) & (left ^ result)) < 0
        : ((left ^ result) & (right ^ result)) < 0;
  }

  int checkComparisonCode(SqlComparison comparison) {
    return switch (comparison) {
      case EQUAL -> TableSchema.CHECK_EQUAL;
      case NOT_EQUAL -> TableSchema.CHECK_NOT_EQUAL;
      case LESS_THAN -> TableSchema.CHECK_LESS_THAN;
      case LESS_OR_EQUAL -> TableSchema.CHECK_LESS_OR_EQUAL;
      case GREATER_THAN -> TableSchema.CHECK_GREATER_THAN;
      case GREATER_OR_EQUAL -> TableSchema.CHECK_GREATER_OR_EQUAL;
      case HALF_OPEN_RANGE, IN, NOT_IN -> 0;
    };
  }
}
