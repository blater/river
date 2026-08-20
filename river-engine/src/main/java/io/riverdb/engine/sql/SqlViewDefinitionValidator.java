package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlParser;

/** Owns bounded syntax and binding scratch for CREATE VIEW validation. */
final class SqlViewDefinitionValidator {
  private final SqlParser parser = new SqlParser();
  private final BoundSqlStatement bound = new BoundSqlStatement();
  private final SqlBinder binder = new SqlBinder();
  private final SqlBlockPlanBinder blockBinder = new SqlBlockPlanBinder(null);
  private final SqlTemporalZoneNames zones = new SqlTemporalZoneNames();

  StatusCode validate(RelationalSession session, CharSequence viewSql) {
    bound.reset();
    StatusCode status = parser.parseQuery(viewSql, bound.query, bound.command);
    if (status.isOk()) {
      status = SqlStoredViewPolicy.validate(bound.command, bound.query);
    }
    if (status.isOk()) {
      status = SqlStoredViewPolicy.validateZones(bound.command, bound.query, zones);
    }
    if (status.isOk()) {
      status = session.resolveTable(bound.command.tableName(), bound.table);
    }
    if (!status.isOk()) return status;
    if (bound.query.isBlockPipeline()) return blockBinder.bind(session, bound, null);
    if (SqlBinder.isGroupAggregate(bound.command.type())) {
      return binder.bindGroupAggregate(bound.command, bound.query, bound);
    }
    if (bound.command.type() == io.riverdb.sql.SqlCommandType.DISTINCT_SCAN) {
      return binder.bindDistinct(bound.command, bound.query, bound);
    }
    return binder.bindDataCommand(bound.command, bound.query, bound);
  }

  int tableId() {
    return bound.table.tableId();
  }
}
