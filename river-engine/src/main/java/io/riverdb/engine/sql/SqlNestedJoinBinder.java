package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;

/** Binds graph-local JOIN roles and their packed lexical predicate programs. */
final class SqlNestedJoinBinder {
  private final SqlBooleanPredicateBinder booleans = new SqlBooleanPredicateBinder();
  private final SqlPredicateBinder predicates = new SqlPredicateBinder();

  StatusCode bindPredicates(
      SqlCommand command,
      BoundSqlStatement bound,
      BoundSqlQuery query,
      int block) {
    if (command.joinChain() == null) {
      return booleans.bindNested(command, bound, query, block);
    }
    SqlBoundJoinContext context = bound.existingJoinContext(block);
    return context == null
        ? StatusCode.CORRUPTION
        : predicates.bindNestedJoin(command, bound, query, block, context);
  }

  StatusCode borrowRoles(
      RelationalSession session,
      BoundSqlStatement bound,
      int blockIndex,
      BoundSqlQuery.Block block) {
    if (block.joinChain() == null) return StatusCode.OK;
    SqlBoundJoinContext context = bound.joinContext(blockIndex);
    StatusCode status = context.borrowRoles(blockIndex, block);
    if (!status.isOk()) return status;
    for (int role = 0; role < block.roleCount(); role++) {
      status = session.resolveStatistics(
          context.table(role), context.statistics(role));
      if (status != StatusCode.CONFLICT && !status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode bindRootRoles(BoundSqlStatement bound, int root) {
    BoundSqlQuery.Block block = bound.executableQuery.block(root);
    SqlJoinChain joins = block == null ? null : block.joinChain();
    if (joins == null) return StatusCode.OK;
    SqlBoundJoinContext context = bound.existingJoinContext(root);
    if (context == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int role = 0; role < joins.roleCount(); role++) {
      TableDefinition table = context.table(role);
      if (table == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      block.bindRoleTable(role, table);
    }
    context.usePackedScopes(root);
    return StatusCode.OK;
  }
}
