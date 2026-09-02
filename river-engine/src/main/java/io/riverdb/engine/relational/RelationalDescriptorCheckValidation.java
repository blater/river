package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.ExactDecimal128Conversion;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.ColumnConstraintDescriptorSet;
import io.riverdb.engine.schema.TableDescriptor;

/** Allocation-free validation of direct-column descriptor CHECK constraints. */
final class RelationalDescriptorCheckValidation {
  private final ExactDecimal128.Scratch scratch = new ExactDecimal128.Scratch();

  StatusCode validate(TableDescriptor table, SqlValueBuffer values) {
    for (int column = 0; column < table.columnCount(); column++) {
      int comparison = table.columns().checkComparisonAt(column);
      if (comparison == ColumnConstraintDescriptorSet.CHECK_NONE || values.isNull(column)) {
        continue;
      }
      int compared = compare(table, values, column);
      if (!matches(comparison, compared)) return StatusCode.CHECK_VIOLATION;
    }
    return StatusCode.OK;
  }

  private int compare(TableDescriptor table, SqlValueBuffer values, int column) {
    int actualType = table.typeDescriptorAt(column);
    int expectedType = table.columns().checkTypeAt(column);
    long actual = values.valueAt(column);
    long expected = table.columns().checkValueAt(column);
    if (!SqlNumericTypeRules.isNumeric(actualType)
        || !SqlNumericTypeRules.isNumeric(expectedType)) return Long.compare(actual, expected);
    boolean actualWide = SqlTypeDescriptor.isWideDecimal(actualType);
    boolean expectedWide = SqlTypeDescriptor.isWideDecimal(expectedType);
    if (!actualWide && !expectedWide) {
      return SqlNumericValue.compare(actual, actualType, expected, expectedType);
    }
    long actualHigh = actualWide ? values.highValueAt(column) : actual >> 63;
    long expectedHigh = expectedWide
        ? table.columns().checkHighAt(column) : expected >> 63;
    if (SqlNumericTypeRules.isExact(actualType) && SqlNumericTypeRules.isExact(expectedType)) {
      return ExactDecimal128.compare(
          actualHigh, actual, scale(actualType),
          expectedHigh, expected, scale(expectedType), scratch);
    }
    return Double.compare(
        doubleValue(actualHigh, actual, actualType),
        doubleValue(expectedHigh, expected, expectedType));
  }

  private static boolean matches(int comparison, int compared) {
    return switch (comparison) {
      case ColumnConstraintDescriptorSet.CHECK_EQUAL -> compared == 0;
      case ColumnConstraintDescriptorSet.CHECK_NOT_EQUAL -> compared != 0;
      case ColumnConstraintDescriptorSet.CHECK_LESS_THAN -> compared < 0;
      case ColumnConstraintDescriptorSet.CHECK_LESS_OR_EQUAL -> compared <= 0;
      case ColumnConstraintDescriptorSet.CHECK_GREATER_THAN -> compared > 0;
      case ColumnConstraintDescriptorSet.CHECK_GREATER_OR_EQUAL -> compared >= 0;
      default -> false;
    };
  }

  private double doubleValue(long high, long low, int descriptor) {
    if (!SqlTypeDescriptor.isWideDecimal(descriptor)) {
      return SqlNumericValue.doubleValue(low, descriptor);
    }
    return ExactDecimal128Conversion.toDouble(high, low, scale(descriptor), scratch);
  }

  private static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }
}
