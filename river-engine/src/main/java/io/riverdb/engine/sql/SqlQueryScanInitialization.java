package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommandType;

/** Stateless command validation for a freshly reset scan execution. */
final class SqlQueryScanInitialization {
  private SqlQueryScanInitialization() { }

  static StatusCode validate(BoundSqlQuery.Block command, BoundSqlQuery query) {
    SqlCommandType type = command.type();
    if (type == SqlCommandType.SHOW_TABLES
        || type == SqlCommandType.SHOW_INDEXES
        || type == SqlCommandType.SHOW_COLUMNS) {
      return query.isExplain() ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
    }
    return query.isExplain()
        && (type == SqlCommandType.NEXT_SEQUENCE_VALUE
            || type == SqlCommandType.SCALAR_EXPRESSION)
        ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }
}
