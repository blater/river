package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;

/** Estimates rows visited through a constrained primary-key prefix. */
final class SqlJoinOrderPrimaryPrefixCost {
  private final SqlJoinOrderFilterCost filter;

  SqlJoinOrderPrimaryPrefixCost(SqlJoinOrderFilterCost sharedFilter) {
    filter = sharedFilter;
  }

  double rows(SqlCommand command, SqlBoundJoinContext context, int role) {
    long tableRows = context.statistics(role).rowCount();
    if (tableRows <= 0) return -1;
    TableDefinition table = context.table(role);
    SqlBooleanPredicateProgram where = command.wherePredicates();
    double scanned = tableRows;
    boolean matched = false;
    for (int part = 0; part < table.primaryIndexPartCount(); part++) {
      int column = table.primaryIndexColumnAt(part);
      double equality = -1;
      double range = -1;
      for (int leaf = 0; leaf < where.leafCount(); leaf++) {
        if (!filter.estimate(command, context, role, leaf)
            || filter.column() != column) continue;
        double rows = filter.rows();
        if (filter.equality()) {
          equality = equality < 0 ? rows : Math.min(equality, rows);
        } else {
          range = range < 0 ? rows : Math.min(range, rows);
        }
      }
      if (equality >= 0) {
        scanned *= equality / tableRows;
      } else if (range >= 0) {
        scanned *= range / tableRows;
      } else {
        break;
      }
      matched = true;
      if (equality < 0) break;
    }
    return matched ? scanned : -1;
  }
}
