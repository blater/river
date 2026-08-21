package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Binds one validated durable-view definition and its ordered physical lineage. */
final class SqlViewDefinitionBinder {
  private final SqlBinder binder;
  private final SqlBlockPlanBinder blocks;

  SqlViewDefinitionBinder(SqlBinder sharedBinder) {
    binder = sharedBinder;
    blocks = new SqlBlockPlanBinder(null, binder);
  }

  StatusCode bind(RelationalSession session, BoundSqlStatement bound) {
    if (bound.query.isBlockPipeline()) {
      return bindPipeline(session, bound);
    }
    StatusCode status = session.resolveTable(
        bound.command.tableName(), bound.table);
    if (!status.isOk()) return status;
    SqlCommandType type = bound.command.type();
    if (type == SqlCommandType.JOIN_SCAN) {
      return bindJoin(session, bound, bound.command);
    }
    if (SqlBinder.isGroupAggregate(type)) {
      return binder.bindGroupAggregate(bound.command, bound.query, bound);
    }
    if (type == SqlCommandType.DISTINCT_SCAN) {
      return binder.bindDistinct(bound.command, bound.query, bound);
    }
    return binder.bindDataCommand(bound.command, bound.query, bound);
  }

  private StatusCode bindPipeline(
      RelationalSession session, BoundSqlStatement bound) {
    SqlCommand deepest = bound.query.block(bound.query.blockCount() - 1);
    if (deepest.type() == SqlCommandType.JOIN_SCAN) {
      StatusCode status = resolveJoin(session, bound, deepest);
      if (!status.isOk()) return status;
    }
    return blocks.bind(session, bound, null);
  }

  private StatusCode bindJoin(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlCommand command) {
    StatusCode status = resolveRight(session, bound, command);
    if (status.isOk()) status = binder.bindJoin(command, bound);
    return status.isOk() && command.joinChain().stageCount() > 1
        ? StatusCode.FEATURE_NOT_SUPPORTED : status;
  }

  private StatusCode resolveJoin(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlCommand command) {
    StatusCode status = binder.resolveJoinRoles(session, command, bound, true);
    return status.isOk() ? rejectRepeatedPhysicalRole(bound, command) : status;
  }

  private StatusCode resolveRight(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlCommand command) {
    StatusCode status = binder.resolveJoinRoles(session, command, bound, false);
    return status.isOk() ? rejectRepeatedPhysicalRole(bound, command) : status;
  }

  private static StatusCode rejectRepeatedPhysicalRole(
      BoundSqlStatement bound, SqlCommand command) {
    for (int right = 1; right < command.joinChain().roleCount(); right++) {
      for (int left = 0; left < right; left++) {
        if (bound.joinRole(left).tableId() == bound.joinRole(right).tableId()) {
          return StatusCode.FEATURE_NOT_SUPPORTED;
        }
      }
    }
    return StatusCode.OK;
  }
}
