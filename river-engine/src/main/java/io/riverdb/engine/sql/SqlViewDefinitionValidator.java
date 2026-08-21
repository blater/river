package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlParser;

/** Owns bounded syntax and binding scratch for CREATE VIEW validation. */
final class SqlViewDefinitionValidator {
  private final SqlParser parser = new SqlParser();
  private final BoundSqlStatement bound = new BoundSqlStatement();
  private final SqlViewDefinitionBinder bindings;
  private final SqlTemporalZoneNames zones = new SqlTemporalZoneNames();

  SqlViewDefinitionValidator(SqlBinder sharedBinder) {
    bindings = new SqlViewDefinitionBinder(sharedBinder);
  }

  StatusCode validate(RelationalSession session, CharSequence viewSql) {
    bound.reset();
    StatusCode status = parser.parseQuery(viewSql, bound.query, bound.command);
    if (status.isOk()) {
      status = SqlStoredViewPolicy.validate(bound.command, bound.query);
    }
    if (status.isOk()) {
      status = SqlStoredViewPolicy.validateZones(bound.command, bound.query, zones);
    }
    return status.isOk() ? bindings.bind(session, bound) : status;
  }

  int tableId() {
    return bound.table.tableId();
  }

  int joinTableId() {
    return bound.joinRole(1) == null ? 0 : bound.joinRole(1).tableId();
  }
}
