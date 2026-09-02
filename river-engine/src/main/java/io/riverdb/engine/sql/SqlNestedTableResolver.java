package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;

/** Resolves one nested role to a binder view without changing its physical owner. */
final class SqlNestedTableResolver {
  private final SqlBindingTableResolver tables = new SqlBindingTableResolver();

  StatusCode resolveRoles(
      RelationalSession session, BoundSqlQuery query, int blockIndex) {
    BoundSqlQuery.Block block = query == null ? null : query.block(blockIndex);
    if (block == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int role = 0; role < block.roleCount(); role++) {
      StatusCode status = resolve(
          session, block.roleTableName(role), block.writableTable(role));
      if (!status.isOk()) return status;
      if (tables.descriptor()) block.markDescriptorRole(role);
    }
    return StatusCode.OK;
  }

  StatusCode resolveContextRoles(
      RelationalSession session,
      SqlCommand command,
      SqlBoundJoinContext context) {
    if (command == null || command.joinChain() == null || context == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int roles = command.joinChain().roleCount();
    StatusCode status = context.beginRoles(roles, null);
    for (int role = 0; status.isOk() && role < roles; role++) {
      status = resolve(
          session, command.joinChain().tableName(role), context.table(role));
      if (status.isOk()) {
        status = session.resolveStatistics(
            context.table(role), context.statistics(role));
        if (status == StatusCode.CONFLICT) status = StatusCode.OK;
      }
    }
    return status;
  }

  StatusCode resolveTable(
      RelationalSession session, CharSequence name, TableDefinition target) {
    return resolve(session, name, target);
  }

  private StatusCode resolve(
      RelationalSession session, CharSequence name, TableDefinition target) {
    return tables.resolve(session, name, target);
  }
}
