package io.riverdb.engine.sql;

import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlComparison;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Allocation-free primitive SQL value, NULL, comparison, and text semantics. */
final class SqlExpressionEvaluator {
  private final ExactDecimal128.Scratch decimal128 = new ExactDecimal128.Scratch();
  long readColumn(
      long primaryKey, HeapRowResult source, TableDefinition table, int column) {
    return column == 0
        ? primaryKey : source.getLong(table.valueOffset(column));
  }

  long readColumnHigh(
      long primaryKey, HeapRowResult source, TableDefinition table, int column) {
    if (column == 0) return primaryKey >> 63;
    long low = source.getLong(table.valueOffset(column));
    return SqlTypeDescriptor.isWideDecimal(table.typeDescriptor(column))
        ? source.getLong(table.highValueOffset(column)) : low >> 63;
  }

  boolean isNull(
      HeapRowResult source,
      TableDefinition definition,
      int column) {
    return column > 0
        && SqlPhysicalRowNulls.get(source, definition, column);
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
    return SqlNumericTypeRules.isNumeric(leftDescriptor)
            && SqlNumericTypeRules.isNumeric(rightDescriptor)
        ? SqlNumericValue.compare(left, leftDescriptor, right, rightDescriptor)
        : Long.compare(left, right);
  }

  int compareExact(
      long leftHigh,
      long left,
      int leftDescriptor,
      long rightHigh,
      long right,
      int rightDescriptor) {
    return SqlNumericTypeRules.isNumeric(leftDescriptor)
            && SqlNumericTypeRules.isNumeric(rightDescriptor)
        ? SqlNumericComparison.compare(
            leftHigh, left, leftDescriptor,
            rightHigh, right, rightDescriptor,
            decimal128)
        : Long.compare(left, right);
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
