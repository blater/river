package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ViewDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;

/** Validates and composes a catalog-decoded view using statement-owned query scratch. */
final class SqlPersistedViewCompiler {
  private final SqlParser parser = new SqlParser();
  private final SqlCommand viewCommand = new SqlCommand();
  private final SqlBlockPlanBinder blockBinder = new SqlBlockPlanBinder();
  private final SqlTemporalZoneNames zones = new SqlTemporalZoneNames();
  private final ViewDefinition definition = new ViewDefinition();

  StatusCode expand(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlBinder binder,
      StatusCode tableStatus) {
    if (tableStatus != StatusCode.CONFLICT
        && tableStatus != StatusCode.CORRUPTION) {
      return tableStatus;
    }
    StatusCode status = session.resolveView(bound.command.tableName(), definition);
    if (!status.isOk()) {
      return tableStatus == StatusCode.CORRUPTION
              && status == StatusCode.CONFLICT
          ? tableStatus : status;
    }
    if (bound.query.hasNestedTopology()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return parseAndCompose(session, bound);
  }

  private StatusCode parseAndCompose(
      RelationalSession session, BoundSqlStatement bound) {
    int storedDepth = parser.queryBlockDepth(definition);
    int outerDepth = Math.max(1, bound.query.blockCount());
    if (storedDepth < 1 || storedDepth >= io.riverdb.sql.SqlQuery.MAXIMUM_QUERY_BLOCKS) {
      return StatusCode.CORRUPTION;
    }
    if (outerDepth + storedDepth > io.riverdb.sql.SqlQuery.MAXIMUM_QUERY_BLOCKS) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    StatusCode status = bound.query.blockCount() == 0
        ? bound.query.appendRootBlock(bound.command) : StatusCode.OK;
    int firstStoredBlock = bound.query.blockCount();
    if (status.isOk()) {
      status = parser.parseQueryAppend(definition, bound.query, viewCommand);
    }
    if (status == StatusCode.QUERY_TOO_COMPLEX) return status;
    if (!status.isOk()
        || !SqlStoredViewPolicy.validateExpanded(viewCommand).isOk()
        || !SqlStoredViewPolicy.validateZones(
            bound.query, firstStoredBlock, zones).isOk()) {
      return StatusCode.CORRUPTION;
    }
    if (!bound.query.validateAppendedPipeline(firstStoredBlock).isOk()) {
      return StatusCode.CORRUPTION;
    }
    SqlCommand base = bound.query.block(bound.query.blockCount() - 1);
    status = session.resolveTable(base.tableName(), bound.table);
    if (!status.isOk() || bound.table.tableId() != definition.baseTableId()) {
      return StatusCode.CORRUPTION;
    }
    if (!blockBinder.validateTail(bound, firstStoredBlock).isOk()) {
      return StatusCode.CORRUPTION;
    }
    status = bound.query.expandRootSelectAllFrom(firstStoredBlock);
    if (!status.isOk()) return status;
    return bound.query.compileCombined(bound.command);
  }
}
