package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.LocalTemporalCast;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Evaluates persisted deterministic CHECK programs against a candidate row. */
final class TableCheckEvaluator {
  private final TableCheckStack stack = new TableCheckStack();
  private final LocalTemporal.Value temporal = new LocalTemporal.Value();
  private int size;

  StatusCode evaluate(TableDefinition table, long primaryKey, ByteBuffer row) {
    StatusCode status = stack.reserve(table.checkNodeCount());
    return status.isOk() ? evaluatePrepared(table, primaryKey, row) : status;
  }

  private StatusCode evaluatePrepared(
      TableDefinition table, long primaryKey, ByteBuffer row) {
    for (int column = 0; column < table.columnCount(); column++) {
      if (!table.hasCheck(column)) continue;
      StatusCode status = program(table, column, primaryKey, row);
      if (!status.isOk()) return status;
      if (!stack.nulls[0] && !matches(
          stack.values[0], stack.descriptors[0], table.checkComparison(column),
          table.checkValue(column), table.checkTypeDescriptor(column))) {
        return StatusCode.CHECK_VIOLATION;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode program(
      TableDefinition table, int column, long primaryKey, ByteBuffer row) {
    size = 0;
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < table.checkNodeCount(column);
        node++) {
      int operator = table.checkOperator(column, node);
      status = operator == TableSchema.CHECK_COLUMN
              || operator == TableSchema.CHECK_LITERAL
          ? leaf(table, column, node, primaryKey, row)
          : operator == TableSchema.CHECK_ADD
                  || operator == TableSchema.CHECK_SUBTRACT
              ? binary(operator, table.checkNodeDescriptor(column, node))
              : unary(
                  operator,
                  table.checkOperand(column, node),
                  table.checkNodeDescriptor(column, node));
    }
    return status.isOk() && size != 1 ? StatusCode.CORRUPTION : status;
  }

  private StatusCode leaf(
      TableDefinition table,
      int column,
      int node,
      long primaryKey,
      ByteBuffer row) {
    if (size >= stack.values.length) return StatusCode.CORRUPTION;
    int operator = table.checkOperator(column, node);
    int descriptor = table.checkNodeDescriptor(column, node);
    stack.nulls[size] = operator == TableSchema.CHECK_COLUMN
        && column > 0 && table.isNull(row, column);
    stack.descriptors[size] = descriptor;
    stack.values[size] = operator == TableSchema.CHECK_LITERAL
        ? table.checkOperand(column, node)
        : stack.nulls[size] ? 0 : column == 0
            ? primaryKey
            : row.getLong(row.position() + table.valueOffset(column));
    size++;
    return StatusCode.OK;
  }

  private StatusCode binary(int operator, int target) {
    if (size < 2) return StatusCode.CORRUPTION;
    int right = --size;
    int left = size - 1;
    if (stack.nulls[left] || stack.nulls[right]) {
      stack.nulls[left] = true;
      stack.descriptors[left] = target;
      return StatusCode.OK;
    }
    StatusCode status = operator == TableSchema.CHECK_ADD
        ? LocalTemporal.addDateDays(stack.values[left], stack.values[right], temporal)
        : SqlTypeDescriptor.typeId(stack.descriptors[right])
                == SqlTypeDescriptor.TYPE_ID_DATE
            ? LocalTemporal.subtractDates(stack.values[left], stack.values[right], temporal)
            : LocalTemporal.subtractDateDays(stack.values[left], stack.values[right], temporal);
    if (status.isOk()) {
      stack.values[left] = temporal.value;
      stack.descriptors[left] = target;
    }
    return status;
  }

  private StatusCode unary(int operator, long operand, int target) {
    if (size < 1) return StatusCode.CORRUPTION;
    int slot = size - 1;
    if (stack.nulls[slot]) {
      stack.descriptors[slot] = target;
      return StatusCode.OK;
    }
    StatusCode status = operator == TableSchema.CHECK_CAST
        ? LocalTemporalCast.castFixed(
            stack.values[slot], stack.descriptors[slot], target, temporal)
        : operator == TableSchema.CHECK_EXTRACT
            ? LocalTemporal.extract(
                stack.values[slot], stack.descriptors[slot], (int) operand, temporal)
            : StatusCode.CORRUPTION;
    if (status.isOk()) {
      stack.values[slot] = temporal.value;
      stack.descriptors[slot] = target;
    }
    return status;
  }

  private static boolean matches(
      long actual,
      int actualDescriptor,
      int comparison,
      long required,
      int requiredDescriptor) {
    int compared = SqlTypeDescriptor.typeId(actualDescriptor)
                == SqlTypeDescriptor.TYPE_ID_DECIMAL
            || SqlTypeDescriptor.typeId(requiredDescriptor)
                == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? ExactDecimal.compare(actual, actualDescriptor, required, requiredDescriptor)
        : SqlNumericTypeRules.isNumeric(actualDescriptor)
            && SqlNumericTypeRules.isNumeric(requiredDescriptor)
            ? SqlNumericValue.compare(
                actual, actualDescriptor, required, requiredDescriptor)
        : Long.compare(actual, required);
    return switch (comparison) {
      case TableSchema.CHECK_EQUAL -> compared == 0;
      case TableSchema.CHECK_NOT_EQUAL -> compared != 0;
      case TableSchema.CHECK_LESS_THAN -> compared < 0;
      case TableSchema.CHECK_LESS_OR_EQUAL -> compared <= 0;
      case TableSchema.CHECK_GREATER_THAN -> compared > 0;
      case TableSchema.CHECK_GREATER_OR_EQUAL -> compared >= 0;
      default -> false;
    };
  }
}
