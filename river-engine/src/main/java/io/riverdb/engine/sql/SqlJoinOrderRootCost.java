package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableStatistics;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;

/** Separates root rows visited from rows accepted before JOIN probes. */
final class SqlJoinOrderRootCost {
  private final SqlJoinOrderFilterCost filter = new SqlJoinOrderFilterCost();
  private final SqlJoinOrderPrimaryPrefixCost primary =
      new SqlJoinOrderPrimaryPrefixCost(filter);
  private double acceptedRows;
  private double scannedRows;
  private boolean indexed;

  void estimate(SqlCommand command, SqlBoundJoinContext context, int role) {
    TableStatistics statistics = context.statistics(role);
    TableDefinition table = context.table(role);
    acceptedRows = statistics.rowCount();
    scannedRows = statistics.rowCount();
    indexed = false;
    SqlBooleanPredicateProgram where = command.wherePredicates();
    for (int leaf = 0; leaf < where.leafCount(); leaf++) {
      if (!filter.estimate(command, context, role, leaf)) continue;
      acceptedRows = Math.min(acceptedRows, filter.rows());
      int column = filter.column();
      if (table.hasPrimaryIndexOn(column) || table.hasIndexOn(column)) {
        scannedRows = Math.min(scannedRows, filter.rows());
        indexed = true;
      }
    }
    double primaryRows = primary.rows(command, context, role);
    if (primaryRows >= 0) {
      scannedRows = Math.min(scannedRows, primaryRows);
      acceptedRows = Math.min(acceptedRows, scannedRows);
      indexed = true;
    }
  }

  double acceptedRows() { return acceptedRows; }

  double scanCost(long tableRows) {
    return indexed
        ? 2 * (scannedRows + SqlTwoRoleJoinOrder.treeDepth(tableRows))
        : tableRows;
  }
}
