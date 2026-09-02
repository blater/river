package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Computes the exact retained lane capacity needed while binding one query block. */
final class SqlBlockShapeAdmission {
  private SqlBlockShapeAdmission() {}

  static StatusCode finishAggregate(
      SqlBlockSchema output,
      SqlPostAggregateProgramBinder having,
      SqlCommand command,
      BoundSqlStatement bound) {
    StatusCode status = output.status();
    if (status.isOk()) status = bound.projectionPrograms.status();
    return status.isOk() ? having.bind(command, bound) : status;
  }

  static StatusCode finishProjection(
      SqlBlockSchema output, BoundSqlStatement bound) {
    StatusCode status = output.status();
    return status.isOk() ? bound.projectionPrograms.status() : status;
  }

  static StatusCode reserve(
      SqlCommand command, BoundSqlStatement bound, SqlCommandType type) {
    int lanes = command.columnCount() + SqlBlockGroupOrderColumns.hiddenCount(command);
    if (SqlBinder.isScalarAggregate(type) || SqlBinder.isGroupAggregate(type)) {
      lanes = Math.max(lanes, SqlBlockAggregateBinder.requiredOperandLanes(command));
    }
    return bound.reserveProjectionColumns(lanes);
  }
}
