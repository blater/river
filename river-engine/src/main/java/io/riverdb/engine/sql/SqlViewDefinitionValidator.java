package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ViewDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlParser;

/** Owns bounded syntax and binding scratch for CREATE VIEW validation. */
final class SqlViewDefinitionValidator {
  private final SqlParser parser = new SqlParser();
  private final BoundSqlStatement bound = new BoundSqlStatement();
  private final SqlViewDefinitionBinder bindings;
  private final SqlTemporalZoneNames zones = new SqlTemporalZoneNames();
  private final int[] tableIds =
      new int[ViewDefinition.MAXIMUM_LINEAGE_TABLES];
  private int tableCount;

  SqlViewDefinitionValidator(SqlBinder sharedBinder) {
    bindings = new SqlViewDefinitionBinder(sharedBinder);
  }

  StatusCode validate(RelationalSession session, CharSequence viewSql) {
    bound.reset();
    clearLineage();
    StatusCode status = parser.parseQuery(viewSql, bound.query, bound.command);
    if (status.isOk()) {
      status = SqlStoredViewPolicy.validate(bound.command, bound.query);
    }
    if (status.isOk()) {
      status = SqlStoredViewPolicy.validateZones(bound.command, bound.query, zones);
    }
    if (status.isOk()) status = bindings.bind(session, bound);
    if (status.isOk()) captureLineage();
    return status;
  }

  int[] tableIds() {
    return tableIds;
  }

  int tableCount() {
    return tableCount;
  }

  private void captureLineage() {
    SqlCommand source = bound.query.isBlockPipeline()
        ? bound.query.block(bound.query.blockCount() - 1) : bound.command;
    tableCount = source.type() == SqlCommandType.JOIN_SCAN
        ? source.joinChain().roleCount() : 1;
    int block = bound.query.isBlockPipeline()
        ? bound.query.blockCount() - 1 : 0;
    SqlBoundJoinContext context = bound.existingJoinContext(block);
    for (int index = 0; index < tableCount; index++) {
      tableIds[index] = source.type() == SqlCommandType.JOIN_SCAN
          ? context.table(index).tableId() : bound.table.tableId();
    }
  }

  private void clearLineage() {
    for (int index = 0; index < tableCount; index++) tableIds[index] = 0;
    tableCount = 0;
  }
}
