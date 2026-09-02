package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Resolves direct-table row projection programs once per statement. */
final class SqlRowProjectionBinder {
  private final SqlRowProjectionProgramBinder programs =
      new SqlRowProjectionProgramBinder();

  StatusCode bind(SqlCommand command, BoundSqlStatement bound) {
    int count = command.isSelectAll()
        ? bound.table.columnCount() : command.columnCount();
    StatusCode reserved = SqlProjectionBindingAdmission.reserve(bound, count);
    if (!reserved.isOk()) return reserved;
    bound.projectionPrograms.begin(count);
    if (command.isSelectAll()) {
      for (int index = 0; index < count; index++) {
        SqlProjectionBindingAdmission.bindRaw(bound, index, index);
      }
      return SqlProjectionBindingAdmission.publish(bound, count);
    }
    for (int index = 0; index < count; index++) {
      StatusCode status = programs.bind(command, bound, index);
      if (!status.isOk()) {
        bound.projectionPrograms.reset();
        return status;
      }
    }
    return SqlProjectionBindingAdmission.publish(bound, count);
  }

  StatusCode bindJoin(
      SqlCommand command,
      BoundSqlStatement bound,
      SqlBoundJoinContext context) {
    int count = command.columnCount();
    StatusCode reserved = SqlProjectionBindingAdmission.reserveJoin(command, bound, count);
    if (!reserved.isOk()) return reserved;
    bound.projectionPrograms.begin(count);
    for (int index = 0; index < count; index++) {
      StatusCode status = programs.bindJoin(command, bound, context, index);
      if (!status.isOk()) {
        bound.projectionPrograms.reset();
        return status;
      }
    }
    return SqlProjectionBindingAdmission.publish(bound, count);
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
    int lanes = SqlProjectionBindingAdmission.aggregateLanes(command, grouped);
    StatusCode reserved = bound.reserveProjectionColumns(lanes);
    if (!reserved.isOk()) return reserved;
    bound.projectionPrograms.begin(lanes);
    if (grouped) {
      for (int expression = 0; expression < command.groupExpressionCount(); expression++) {
        StatusCode status = programs.bindGroupKey(command, bound, expression);
        if (!status.isOk()) return status;
      }
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
    return SqlProjectionBindingAdmission.hasComputed(command);
  }

}
