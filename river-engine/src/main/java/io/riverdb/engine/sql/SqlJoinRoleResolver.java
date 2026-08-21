package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;

/** Resolves a symbol uniquely across the JOIN roles visible to one program. */
final class SqlJoinRoleResolver {
  private int role = -1;
  private int column = -1;

  boolean resolve(
      SqlCommand command,
      BoundSqlStatement bound,
      int symbol,
      int visibleRoles) {
    role = -1;
    column = -1;
    SqlJoinChain joins = command.joinChain();
    CharSequence name = command.projectionSymbolName(symbol);
    CharSequence qualifier = command.projectionSymbolTable(symbol);
    if (joins == null || name == null || qualifier == null
        || visibleRoles < 1 || visibleRoles > joins.roleCount()) return false;
    for (int candidate = 0; candidate < visibleRoles; candidate++) {
      TableDefinition table = table(bound, candidate);
      if (table == null || qualifier.length() > 0
          && !qualified(joins, candidate, qualifier)) continue;
      int resolved = table.findColumn(name);
      if (resolved < 0) continue;
      if (role >= 0) return false;
      role = candidate;
      column = resolved;
    }
    return role >= 0;
  }

  int role() { return role; }
  int column() { return column; }

  static TableDefinition table(BoundSqlStatement bound, int role) {
    return bound.joinRole(role);
  }

  private static boolean qualified(
      SqlJoinChain joins, int role, CharSequence qualifier) {
    return SqlBindingNames.same(qualifier, joins.tableName(role))
        || joins.alias(role).length() > 0
            && SqlBindingNames.same(qualifier, joins.alias(role));
  }
}
