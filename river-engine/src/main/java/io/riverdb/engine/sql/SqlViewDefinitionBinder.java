package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Binds one validated durable-view definition and its ordered physical lineage. */
final class SqlViewDefinitionBinder {
  private final SqlBinder binder;
  private final SqlBlockPlanBinder blocks;
  private final SqlBindingTableResolver tables = new SqlBindingTableResolver();

  SqlViewDefinitionBinder(SqlBinder sharedBinder) {
    binder = sharedBinder;
    blocks = new SqlBlockPlanBinder(null, binder);
  }

  StatusCode bind(RelationalSession session, BoundSqlStatement bound) {
    if (bound.query.isBlockPipeline()) {
      return bindPipeline(session, bound);
    }
    StatusCode status = tables.resolve(
        session, bound.command.tableName(), bound.table);
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
    StatusCode status = blocks.bind(session, bound, null);
    return status;
  }

  private StatusCode bindJoin(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlCommand command) {
    SqlBoundJoinContext context = bound.joinContext(0);
    StatusCode status = resolveRoles(session, command, context, bound.table, false);
    if (status.isOk()) status = binder.bindJoin(command, bound, context);
    return status;
  }

  private StatusCode resolveJoin(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlCommand command) {
    int block = Math.max(0, bound.query.blockCount() - 1);
    StatusCode status = resolveRoles(
        session, command, bound.joinContext(block), null, true);
    return status;
  }

  private StatusCode resolveRoles(
      RelationalSession session,
      SqlCommand command,
      SqlBoundJoinContext context,
      io.riverdb.engine.relational.TableDefinition root,
      boolean resolveLeft) {
    if (command.joinChain() == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    int roles = command.joinChain().roleCount();
    StatusCode status = context.beginRoles(roles, resolveLeft ? null : root);
    int first = resolveLeft ? 0 : 1;
    for (int role = first; status.isOk() && role < roles; role++) {
      status = tables.resolve(
          session, command.joinChain().tableName(role), context.table(role));
    }
    return status;
  }
}
