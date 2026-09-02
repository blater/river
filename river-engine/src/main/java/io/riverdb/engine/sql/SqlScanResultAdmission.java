package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommandType;

/** Reserves the final scan metadata shape before any physical cursor can advance. */
final class SqlScanResultAdmission {
  private SqlScanResultAdmission() { }

  static StatusCode prepare(
      BoundSqlQuery query,
      SqlPhysicalPlan plan,
      BoundSqlStatement bound,
      SqlCommandType type) {
    if (!query.isExecutable()
        && type != SqlCommandType.NEXT_SEQUENCE_VALUE
        && type != SqlCommandType.SCALAR_EXPRESSION
        && !SqlBinder.isScalarAggregate(type)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int columns = bound.projectedColumnCount;
    if (type == SqlCommandType.DISTINCT_SCAN) columns = bound.projectedColumnCount;
    else if (SqlBinder.isGroupAggregate(type)) columns = bound.projectedColumnCount;
    else if (SqlBinder.isScalarAggregate(type)
        || type == SqlCommandType.NEXT_SEQUENCE_VALUE
        || type == SqlCommandType.SCALAR_EXPRESSION) columns = 1;
    return plan.beginResult(columns);
  }
}
