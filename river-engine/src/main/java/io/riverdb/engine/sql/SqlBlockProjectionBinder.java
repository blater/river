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
      BoundSqlStatement bound,
      boolean finalBlock) {
    int visible = command.columnCount();
    if (visible <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int contributors = finalBlock ? orderContributorCount(command, child) : 0;
    if (contributors < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int count = visible + contributors;
    StatusCode reserved = bound.reserveProjectionColumns(count);
    if (!reserved.isOk()) return reserved;
    bound.projectionPrograms.begin(count);
    output.set(count);
    for (int lane = 0; lane < visible; lane++) {
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
    int lane = visible;
    for (int order = 0; order < command.orderExpressionCount(); order++) {
      if (visibleOrder(command, order) || priorOrder(command, order)) continue;
      int column = child.find(command.orderColumnName(order));
      if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      int descriptor = child.descriptor(column);
      bound.projectionPrograms.append(
          lane, SqlScalarExpression.COLUMN, column, descriptor);
      bound.projectionPrograms.finish(lane, descriptor, column);
      output.setColumn(
          lane, command.orderColumnName(order), descriptor, child.nullable(column));
      bound.projectedColumns[lane] = column;
      bound.projectedTypeDescriptors[lane] = descriptor;
      lane++;
    }
    bound.projectedColumnCount = count;
    if (command.type() == SqlCommandType.DISTINCT_SCAN) {
      bound.distinctColumn = bound.projectionPrograms.rawColumn(0);
      bound.sortKeyProjection = bound.distinctColumn < 0 ? 0 : -1;
    }
    return SqlBlockShapeAdmission.finishProjection(output, bound);
  }

  private static int orderContributorCount(SqlCommand command, SqlBlockSchema child) {
    if (!command.isOrdered()) return 0;
    int count = 0;
    for (int order = 0; order < command.orderExpressionCount(); order++) {
      if (visibleOrder(command, order) || priorOrder(command, order)) continue;
      if (child.find(command.orderColumnName(order)) < 0) return -1;
      count++;
    }
    return count;
  }

  private static boolean visibleOrder(SqlCommand command, int order) {
    if (command.orderColumnTableName(order).length() > 0) {
      return SqlProjectionBinder.resolveOrderProjection(command, order) >= 0;
    }
    CharSequence name = command.orderColumnName(order);
    for (int projection = 0; projection < command.columnCount(); projection++) {
      if (SqlBindingNames.same(command.columnOutputName(projection), name)
          || SqlBindingNames.same(command.columnName(projection), name)) return true;
    }
    return false;
  }

  private static boolean priorOrder(SqlCommand command, int order) {
    for (int prior = 0; prior < order; prior++) {
      if (SqlBindingNames.same(
              command.orderColumnTableName(prior), command.orderColumnTableName(order))
          && SqlBindingNames.same(
              command.orderColumnName(prior), command.orderColumnName(order))) return true;
    }
    return false;
  }

  StatusCode publishOperandSchema(
      BoundSqlStatement bound,
      int block,
      SqlBlockSchema child,
      SqlCommandType type) {
    SqlBoundBlockPlans plans = bound.blockPlans();
    SqlBlockSchema operands = plans.operandSchema(block);
    if (!SqlBinder.isScalarAggregate(type) && !SqlBinder.isGroupAggregate(type)) {
      operands.copyFrom(plans.schema(block));
      return operands.status();
    }
    operands.set(bound.projectionPrograms.count());
    for (int lane = 0; lane < bound.projectionPrograms.count(); lane++) {
      int source = bound.projectionPrograms.rawColumn(lane);
      operands.setColumn(
          lane,
          "",
          bound.projectionPrograms.resultDescriptor(lane),
          source >= 0 ? child.nullable(source)
              : expressions.nullable(
                  bound.command,
                  bound.command.aggregateOperandExpression(lane),
                  child));
    }
    return operands.status();
  }
}
