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
  private final SqlBinder binder;
  private final SqlBlockPlanBinder blockBinder;
  private final SqlTemporalZoneNames zones = new SqlTemporalZoneNames();
  private final ViewDefinition definition = new ViewDefinition();

  SqlPersistedViewCompiler(SqlBinder binder) {
    this.binder = binder;
    blockBinder = new SqlBlockPlanBinder(null, binder);
  }

  StatusCode expand(
      RelationalSession session,
      BoundSqlStatement bound,
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
    status = resolveLineage(session, bound, base);
    if (!status.isOk()) {
      return StatusCode.CORRUPTION;
    }
    StatusCode tailStatus = blockBinder.validateTail(bound, firstStoredBlock);
    bound.blockPlans().reset();
    if (!tailStatus.isOk()) {
      return StatusCode.CORRUPTION;
    }
    status = bound.query.expandRootSelectAllFrom(1);
    if (!status.isOk()) return status;
    return bound.query.compileCombined(bound.command);
  }

  private StatusCode resolveLineage(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlCommand base) {
    boolean join = base.type() == io.riverdb.sql.SqlCommandType.JOIN_SCAN;
    int block = Math.max(0, bound.query.blockCount() - 1);
    SqlBoundJoinContext context = join ? bound.joinContext(block) : null;
    StatusCode status = join
        ? binder.resolveJoinRoles(session, base, context, null, true)
        : session.resolveTable(base.tableName(), bound.table);
    if (!status.isOk()) return StatusCode.CORRUPTION;
    int count = join ? base.joinChain().roleCount() : 1;
    if (definition.tableCount() != count) return StatusCode.CORRUPTION;
    for (int index = 0; index < count; index++) {
      int rebound = join
          ? context.table(index).tableId() : bound.table.tableId();
      if (definition.tableId(index) != rebound) {
        return StatusCode.CORRUPTION;
      }
    }
    return StatusCode.OK;
  }
}
