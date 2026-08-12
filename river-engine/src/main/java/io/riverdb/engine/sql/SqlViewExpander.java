package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ViewDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;

/** Owns parser scratch used while resolving a view to its stable bound command. */
final class SqlViewExpander {
  private final SqlParser parser = new SqlParser();
  private final SqlCommand viewCommand = new SqlCommand();
  private final ViewDefinition viewDefinition = new ViewDefinition();

  StatusCode resolve(RelationalSession session, BoundSqlStatement bound) {
    StatusCode tableStatus = session.resolveTable(
        bound.command.tableName(), bound.table);
    if (tableStatus.isOk()) {
      return tableStatus;
    }
    if (tableStatus != StatusCode.CONFLICT
        && tableStatus != StatusCode.CORRUPTION) {
      return tableStatus;
    }
    StatusCode status = session.resolveView(
        bound.command.tableName(), viewDefinition);
    if (!status.isOk()) {
      return tableStatus == StatusCode.CORRUPTION
              && status == StatusCode.CONFLICT
          ? tableStatus : status;
    }
    status = parser.parse(viewDefinition, viewCommand);
    if (status.isOk()) {
      status = bound.query.compileView(
          bound.command, viewCommand, bound.command);
    }
    bound.query.reset();
    if (status.isOk()) {
      status = session.resolveTable(bound.command.tableName(), bound.table);
      if (status.isOk()
          && bound.table.tableId() != viewDefinition.baseTableId()) {
        status = StatusCode.CORRUPTION;
      }
    }
    return status;
  }
}
