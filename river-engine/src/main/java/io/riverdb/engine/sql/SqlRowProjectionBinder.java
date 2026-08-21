package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves direct-table row projection programs once per statement. */
final class SqlRowProjectionBinder {
  private final SqlRowProjectionProgramBinder programs =
      new SqlRowProjectionProgramBinder();

  StatusCode bind(SqlCommand command, BoundSqlStatement bound) {
    int count = command.isSelectAll()
        ? bound.table.columnCount() : command.columnCount();
    if (count <= 0 || count > bound.projectedColumns.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bound.projectionPrograms.begin(count);
    if (command.isSelectAll()) {
      for (int index = 0; index < count; index++) {
        bindRaw(bound, index, index);
      }
      return publish(bound, count);
    }
    for (int index = 0; index < count; index++) {
      StatusCode status = programs.bind(command, bound, index);
      if (!status.isOk()) {
        bound.projectionPrograms.reset();
        return status;
      }
    }
    return publish(bound, count);
  }

  StatusCode bindJoin(
      SqlCommand command,
      BoundSqlStatement bound,
      SqlBoundJoinContext context) {
    int count = command.columnCount();
    if (command.isSelectAll() || count <= 0 || count > bound.projectedColumns.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bound.projectionPrograms.begin(count);
    for (int index = 0; index < count; index++) {
      StatusCode status = programs.bindJoin(command, bound, context, index);
      if (!status.isOk()) {
        bound.projectionPrograms.reset();
        return status;
      }
    }
    return publish(bound, count);
  }

  StatusCode bindOrderAlias(
      SqlCommand command, BoundSqlStatement bound, int projection) {
    int column = bound.projectionPrograms.rawColumn(projection);
    if (column < 0) {
      StatusCode status = validateComputedKey(command, bound, projection);
      if (!status.isOk()) return status;
      bound.orderColumn = SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
      bound.sortKeyProjection = projection;
      return StatusCode.OK;
    }
    bound.orderColumn = column;
    bound.sortKeyProjection = -1;
    return StatusCode.OK;
  }

  StatusCode bindAggregateOperands(
      SqlCommand command, BoundSqlStatement bound, boolean grouped) {
    int lanes = grouped ? 1 : 0;
    for (int invocation = 0;
        invocation < command.aggregateInvocationCount(); invocation++) {
      int lane = command.aggregateOperandProjection(invocation);
      if (lane >= lanes) lanes = lane + 1;
    }
    bound.projectionPrograms.begin(lanes);
    if (grouped) {
      StatusCode status = programs.bindAggregateOperand(command, bound, 0);
      if (!status.isOk()) return status;
    }
    for (int invocation = 0;
        invocation < command.aggregateInvocationCount(); invocation++) {
      int lane = command.aggregateOperandProjection(invocation);
      if (lane < 0) continue;
      StatusCode status = programs.bindAggregateOperand(command, bound, lane);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode validateComputedKey(
      SqlCommand command, BoundSqlStatement bound, int projection) {
    return SqlPrimitiveSortKey.validate(command, bound, projection);
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

  private static void bindRaw(
      BoundSqlStatement bound, int projection, int column) {
    int descriptor = bound.table.typeDescriptor(column);
    bound.projectionPrograms.append(
        projection, SqlScalarExpression.COLUMN, column, descriptor);
    bound.projectionPrograms.finish(projection, descriptor, column);
    bound.projectedColumns[projection] = column;
  }

  private static StatusCode publish(BoundSqlStatement bound, int count) {
    bound.projectedColumnCount = count;
    for (int index = 0; index < count; index++) {
      bound.projectedTypeDescriptors[index] =
          bound.projectionPrograms.resultDescriptor(index);
    }
    return StatusCode.OK;
  }

}
