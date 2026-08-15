package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;

/** Resolves every selected or hidden aggregate invocation against physical operand lanes. */
final class SqlAggregateSetBinder {
  private final SqlRowProjectionBinder rows;

  SqlAggregateSetBinder(SqlRowProjectionBinder rowBinder) {
    rows = rowBinder;
  }

  StatusCode bind(SqlCommand command, BoundSqlStatement bound, boolean grouped) {
    StatusCode status = rows.bindAggregateOperands(command, bound, grouped);
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregateInvocationCount(); invocation++) {
      status = bindInvocation(command, bound, invocation);
    }
    if (!status.isOk()) return status;
    int selected = command.aggregateOutputInvocation(0);
    if (selected < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int output = grouped ? 1 : 0;
    bound.projectedTypeDescriptors[output] =
        bound.aggregates.resultDescriptor(selected);
    bound.projectedColumnCount = grouped ? 2 : 1;
    int lane = bound.aggregates.operandLane(selected);
    int column = lane < 0 ? -1 : bound.projectionPrograms.rawColumn(lane);
    bound.projectedColumns[output] = lane < 0
        ? BoundSqlStatement.NULL_PROJECTION
        : column >= 0 ? column : SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
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
    if (kind == SqlAggregateKind.COUNT) return StatusCode.OK;
    int lane = command.aggregateOperandProjection(invocation);
    if (lane < 0 || !command.aggregateOperandExpression(lane).hasColumnReference()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    int family = SqlTypeDescriptor.comparisonFamily(descriptor);
    if ((kind == SqlAggregateKind.SUM || kind == SqlAggregateKind.AVG)
        && family != SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if ((kind == SqlAggregateKind.MIN || kind == SqlAggregateKind.MAX)
        && family == SqlTypeDescriptor.COMPARISON_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return StatusCode.OK;
  }
}
