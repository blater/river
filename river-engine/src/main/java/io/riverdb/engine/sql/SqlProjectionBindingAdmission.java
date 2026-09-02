package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Centralized shape admission and publication for row projection binding. */
final class SqlProjectionBindingAdmission {
  private SqlProjectionBindingAdmission() { }

  static StatusCode reserve(BoundSqlStatement bound, int count) {
    return count <= 0
        ? StatusCode.INVALID_EXTERNAL_INPUT : bound.reserveProjectionColumns(count);
  }

  static StatusCode reserveJoin(
      SqlCommand command, BoundSqlStatement bound, int count) {
    return command.isSelectAll() || count <= 0
        ? StatusCode.INVALID_EXTERNAL_INPUT : bound.reserveProjectionColumns(count);
  }

  static StatusCode publish(BoundSqlStatement bound, int count) {
    StatusCode status = bound.projectionPrograms.status();
    if (!status.isOk()) return status;
    bound.projectedColumnCount = count;
    for (int index = 0; index < count; index++) {
      bound.projectedTypeDescriptors[index] =
          bound.projectionPrograms.resultDescriptor(index);
    }
    return StatusCode.OK;
  }

  static boolean hasComputed(SqlCommand command) {
    for (int index = 0; index < command.columnCount(); index++) {
      SqlScalarExpression expression = command.projectionExpression(index);
      if (expression != null && expression.isAvailable()
          && !expression.isDirectColumnReference()
          && !expression.isNullLiteral()) {
        return true;
      }
    }
    return false;
  }

  static void bindRaw(BoundSqlStatement bound, int projection, int column) {
    int descriptor = bound.table.typeDescriptor(column);
    bound.projectionPrograms.append(
        projection, SqlScalarExpression.COLUMN, column, descriptor);
    bound.projectionPrograms.finish(projection, descriptor, column);
    bound.projectedColumns[projection] = column;
  }

  static int aggregateLanes(SqlCommand command, boolean grouped) {
    int lanes = grouped ? command.groupExpressionCount() : 0;
    for (int invocation = 0;
        invocation < command.aggregateInvocationCount(); invocation++) {
      int lane = command.aggregateOperandProjection(invocation);
      if (lane >= lanes) lanes = lane + 1;
    }
    return lanes;
  }
}
