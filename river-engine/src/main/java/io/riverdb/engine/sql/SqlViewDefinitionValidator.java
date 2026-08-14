package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlQuery;

/** Owns bounded syntax and binding scratch for CREATE VIEW validation. */
final class SqlViewDefinitionValidator {
  private final SqlParser parser = new SqlParser();
  private final SqlCommand command = new SqlCommand();
  private final SqlQuery query = new SqlQuery();
  private final BoundSqlStatement bound = new BoundSqlStatement();
  private final SqlBinder binder = new SqlBinder();

  StatusCode validate(RelationalSession session, CharSequence viewSql) {
    query.reset();
    StatusCode status = parser.parse(viewSql, command);
    if (!status.isOk()
        || command.type() != SqlCommandType.SCAN
            && command.type() != SqlCommandType.SELECT
        || command.isSelectAll()
        || command.columnCount() <= 0
        || command.isOrdered()
        || command.rowLimit() != Long.MAX_VALUE
        || command.hasDisjunction()) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    bound.reset();
    status = session.resolveTable(command.tableName(), bound.table);
    return status.isOk()
        ? binder.bindDataCommand(
            command, query, bound)
        : status;
  }

  int tableId() {
    return bound.table.tableId();
  }
}
