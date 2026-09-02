package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Binds compact descriptor set key and aggregate-operand expression lanes. */
final class SqlDescriptorSetBinding {
  private final SqlBlockExpressionBinder expressions = new SqlBlockExpressionBinder();
  private int laneCount;

  StatusCode bind(
      SqlCommand command,
      SqlBlockSchema input,
      BoundSqlStatement bound,
      int keys,
      int[] aggregateLanes) {
    laneCount = keys + aggregateOperandCount(command);
    StatusCode status = bound.reserveProjectionColumns(laneCount);
    if (status.isOk()) bound.projectionPrograms.begin(laneCount);
    for (int key = 0; status.isOk() && key < keys; key++) {
      status = expressions.bind(command, keyExpression(command, key), key, input, bound);
    }
    int lane = keys;
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregateInvocationCount(); invocation++) {
      int projection = command.aggregateOperandProjection(invocation);
      aggregateLanes[invocation] = projection < 0 ? -1 : lane;
      if (projection >= 0) status = expressions.bind(
          command, command.aggregateOperandExpression(projection), lane++, input, bound);
    }
    return status.isOk() ? bound.projectionPrograms.status() : status;
  }

  StatusCode describe(
      SqlCommand command,
      SqlBlockSchema input,
      BoundSqlStatement bound,
      SqlBlockSchema output,
      int keys) {
    output.set(laneCount);
    for (int lane = 0; lane < laneCount; lane++) {
      output.setColumn(
          lane,
          "",
          bound.projectionPrograms.resultDescriptor(lane),
          lane < keys && expressions.nullable(
              command, keyExpression(command, lane), input));
    }
    return output.status();
  }

  int laneCount() { return laneCount; }

  private static SqlScalarExpression keyExpression(SqlCommand command, int key) {
    return command.groupExpressionCount() > 0
        ? command.groupExpression(key) : command.projectionExpression(key);
  }

  private static int aggregateOperandCount(SqlCommand command) {
    int count = 0;
    for (int invocation = 0; invocation < command.aggregateInvocationCount(); invocation++) {
      if (command.aggregateOperandProjection(invocation) >= 0) count++;
    }
    return count;
  }
}
