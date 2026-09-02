package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlGroupExpressions;

/** Resolves every selected or hidden aggregate invocation against physical operand lanes. */
final class SqlAggregateSetBinder {
  private final SqlRowProjectionBinder rows;

  SqlAggregateSetBinder(SqlRowProjectionBinder rowBinder) {
    rows = rowBinder;
  }

  StatusCode bind(SqlCommand command, BoundSqlStatement bound, boolean grouped) {
    int groups = grouped ? command.groupExpressionCount() : 0;
    StatusCode status = bound.reserveProjectionColumns(
        Math.max(groups + 1, command.columnCount()));
    if (status.isOk()) {
      status = bound.aggregates.reserve(command.aggregateInvocationCount());
    }
    if (status.isOk()) status = rows.bindAggregateOperands(command, bound, grouped);
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregateInvocationCount(); invocation++) {
      status = bindInvocation(command, bound, invocation);
    }
    if (!status.isOk()) return status;
    int groupOutputs = grouped
        ? command.columnCount() - command.aggregateOutputCount() : 0;
    for (int output = 0; output < groupOutputs; output++) {
      int key = SqlGroupExpressions.groupKey(command, output);
      if (key < 0 || key >= groups) return StatusCode.INVALID_EXTERNAL_INPUT;
      bound.projectedTypeDescriptors[output] =
          bound.projectionPrograms.resultDescriptor(key);
      bound.projectedColumns[output] = bound.projectionPrograms.rawColumn(key);
    }
    for (int output = 0; output < command.aggregateOutputCount(); output++) {
      int invocation = command.aggregateOutputInvocation(output);
      if (invocation < 0 || invocation >= bound.aggregates.count()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int result = groupOutputs + output;
      bound.projectedTypeDescriptors[result] =
          bound.aggregates.resultDescriptor(invocation);
      int operand = bound.aggregates.operandLane(invocation);
      int column = operand < 0 ? -1 : bound.projectionPrograms.rawColumn(operand);
      bound.projectedColumns[result] = operand < 0
          ? BoundSqlStatement.NULL_PROJECTION
          : column >= 0 ? column : SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
    }
    bound.projectedColumnCount = command.columnCount();
    int selected = command.aggregateOutputInvocation(0);
    if (selected < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int lane = bound.aggregates.operandLane(selected);
    int column = lane < 0 ? -1 : bound.projectionPrograms.rawColumn(lane);
    if (grouped) bound.groupAggregateColumn = lane < 0 ? -1
        : column >= 0 ? column : SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
    return StatusCode.OK;
  }

  private static StatusCode bindInvocation(
      SqlCommand command, BoundSqlStatement bound, int invocation) {
    int kind = command.aggregateKind(invocation);
    int lane = command.aggregateOperandProjection(invocation);
    int input = lane < 0
        ? SqlTypeDescriptor.BIGINT
        : bound.projectionPrograms.resultDescriptor(lane);
    StatusCode status = validate(command, invocation, kind, input);
    int result = status.isOk()
        ? SqlProjectionBinder.aggregateResultDescriptor(kind, input) : 0;
    if (status.isOk() && result == 0) status = StatusCode.DATATYPE_MISMATCH;
    if (status.isOk()) bound.aggregates.append(kind, lane, input, result);
    return status;
  }

  private static StatusCode validate(
      SqlCommand command, int invocation, int kind, int descriptor) {
    int lane = command.aggregateOperandProjection(invocation);
    if (kind == SqlAggregateKind.COUNT || kind == SqlAggregateKind.COUNT_DISTINCT) {
      return kind == SqlAggregateKind.COUNT_DISTINCT && lane < 0
          ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
    }
    if (lane < 0 || !command.aggregateOperandExpression(lane).hasColumnReference()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    int family = SqlTypeDescriptor.comparisonFamily(descriptor);
    if ((kind == SqlAggregateKind.SUM || kind == SqlAggregateKind.AVG)
        && !SqlNumericTypeRules.isNumeric(descriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if ((kind == SqlAggregateKind.MIN || kind == SqlAggregateKind.MAX)
        && family == SqlTypeDescriptor.COMPARISON_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return StatusCode.OK;
  }
}
