package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlScalarExpression;

/** Binds one block's visible projections and stable aggregate operand schema. */
final class SqlBlockProjectionBinder {
  private final SqlBlockExpressionBinder expressions;

  SqlBlockProjectionBinder(SqlBlockExpressionBinder expressionBinder) {
    expressions = expressionBinder;
  }

  StatusCode bind(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound) {
    int count = command.columnCount();
    if (count <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    bound.projectionPrograms.begin(count);
    output.set(count);
    for (int lane = 0; lane < count; lane++) {
      SqlScalarExpression expression = command.projectionExpression(lane);
      StatusCode status = expressions.bind(command, expression, lane, child, bound);
      if (!status.isOk()) return status;
      int descriptor = bound.projectionPrograms.resultDescriptor(lane);
      output.setColumn(
          lane,
          command.columnOutputName(lane),
          descriptor,
          expressions.nullable(command, expression, child));
      bound.projectedColumns[lane] = bound.projectionPrograms.rawColumn(lane);
      bound.projectedTypeDescriptors[lane] = descriptor;
    }
    bound.projectedColumnCount = count;
    if (command.type() == SqlCommandType.DISTINCT_SCAN) {
      bound.distinctColumn = bound.projectionPrograms.rawColumn(0);
      bound.sortKeyProjection = bound.distinctColumn < 0 ? 0 : -1;
    }
    return StatusCode.OK;
  }

  void publishOperandSchema(
      BoundSqlStatement bound,
      int block,
      SqlBlockSchema child,
      SqlCommandType type) {
    SqlBoundBlockPlans plans = bound.blockPlans();
    SqlBlockSchema operands = plans.operandSchema(block);
    if (!SqlBinder.isScalarAggregate(type) && !SqlBinder.isGroupAggregate(type)) {
      operands.copyFrom(plans.schema(block));
      return;
    }
    operands.set(bound.projectionPrograms.count());
    for (int lane = 0; lane < bound.projectionPrograms.count(); lane++) {
      operands.setColumn(
          lane,
          "",
          bound.projectionPrograms.resultDescriptor(lane),
          expressions.nullable(
              bound.command,
              bound.command.aggregateOperandExpression(lane),
              child));
    }
  }
}
