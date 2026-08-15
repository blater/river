package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Binds one scalar/grouped aggregate block against a virtual child schema. */
final class SqlBlockAggregateBinder {
  private final SqlBlockExpressionBinder expressions;
  private final SqlPostAggregateProgramBinder having =
      new SqlPostAggregateProgramBinder();

  SqlBlockAggregateBinder(SqlBlockExpressionBinder expressionBinder) {
    expressions = expressionBinder;
  }

  StatusCode bind(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      boolean grouped) {
    int lanes = grouped ? 1 : 0;
    for (int invocation = 0; invocation < command.aggregateInvocationCount(); invocation++) {
      int lane = command.aggregateOperandProjection(invocation);
      if (lane >= lanes) lanes = lane + 1;
    }
    bound.projectionPrograms.begin(lanes);
    StatusCode status = grouped ? bindLane(command, child, bound, 0) : StatusCode.OK;
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregateInvocationCount(); invocation++) {
      int lane = command.aggregateOperandProjection(invocation);
      if (lane >= 0) status = bindLane(command, child, bound, lane);
      if (status.isOk()) status = bindInvocation(command, bound, invocation);
    }
    if (!status.isOk()) return status;
    int selected = command.aggregateOutputInvocation(0);
    if (selected < 0 || selected >= bound.aggregates.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    publish(command, child, output, bound, grouped, selected);
    return having.bind(command, bound);
  }

  private StatusCode bindLane(
      SqlCommand command,
      SqlBlockSchema child,
      BoundSqlStatement bound,
      int lane) {
    SqlScalarExpression expression = command.aggregateOperandExpression(lane);
    return expressions.bind(command, expression, lane, child, bound);
  }

  private static StatusCode bindInvocation(
      SqlCommand command, BoundSqlStatement bound, int invocation) {
    int kind = command.aggregateKind(invocation);
    int lane = command.aggregateOperandProjection(invocation);
    int input = lane < 0
        ? SqlTypeDescriptor.BIGINT : bound.projectionPrograms.resultDescriptor(lane);
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
        && family != SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC
        || (kind == SqlAggregateKind.MIN || kind == SqlAggregateKind.MAX)
            && family == SqlTypeDescriptor.COMPARISON_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return StatusCode.OK;
  }

  private void publish(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      boolean grouped,
      int selected) {
    int aggregateDescriptor = bound.aggregates.resultDescriptor(selected);
    int aggregateKind = bound.aggregates.kind(selected);
    int columns = grouped ? 2 : 1;
    output.set(columns);
    if (grouped) {
      SqlScalarExpression key = command.aggregateOperandExpression(0);
      int keyDescriptor = bound.projectionPrograms.resultDescriptor(0);
      boolean nullable = expressions.nullable(command, key, child);
      output.setColumn(0, command.columnOutputName(0), keyDescriptor, nullable);
      bound.projectedTypeDescriptors[0] = keyDescriptor;
      bound.groupColumn = bound.projectionPrograms.rawColumn(0);
      bound.sortKeyProjection = bound.groupColumn < 0 ? 0 : -1;
    }
    int aggregateColumn = grouped ? 1 : 0;
    output.setColumn(
        aggregateColumn,
        command.columnOutputName(aggregateColumn),
        aggregateDescriptor,
        aggregateKind != SqlAggregateKind.COUNT
            && aggregateKind != SqlAggregateKind.COUNT_VALUE);
    bound.projectedTypeDescriptors[aggregateColumn] = aggregateDescriptor;
    bound.projectedColumnCount = columns;
  }
}
