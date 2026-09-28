package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableStatistics;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.sql.SqlProjectionList;

/** Compares root access and probe work for a safe two-relation inner JOIN. */
final class SqlTwoRoleJoinOrder {
  private final SqlJoinOrderRootCost roots = new SqlJoinOrderRootCost();
  private final SqlJoinOrderProbeCost probes = new SqlJoinOrderProbeCost();

  boolean reverse(SqlCommand command, SqlBoundJoinContext context) {
    SqlJoinChain joins = command.joinChain();
    if (joins == null || joins.roleCount() != 2 || joins.stageCount() != 1
        || joins.joinKind(0) != SqlJoinChain.INNER || command.isSelectAll()
        || !SqlJoinPredicateClassifier.totalJoinOrder(command)
        || !qualified(command)) return false;
    TableStatistics left = context.statistics(0);
    TableStatistics right = context.statistics(1);
    if (left == null || right == null
        || !left.availableFor(context.table(0))
        || !right.availableFor(context.table(1))) return false;
    SqlBooleanPredicateProgram where = command.wherePredicates();
    if (where.isAvailable() && !conjunctive(where, where.root())) return false;
    return cost(command, context, 1) < cost(command, context, 0);
  }

  private double cost(SqlCommand command, SqlBoundJoinContext context, int root) {
    roots.estimate(command, context, root);
    double accepted = roots.acceptedRows();
    return roots.scanCost(context.statistics(root).rowCount())
        + accepted * probes.perRow(command, context, 1 - root);
  }

  static int treeDepth(long rows) {
    return rows <= 1 ? 1 : Long.SIZE - Long.numberOfLeadingZeros(rows - 1);
  }

  private static boolean qualified(SqlCommand command) {
    SqlProjectionList projections = command.projections();
    for (int symbol = 0; symbol < projections.symbolCount(); symbol++) {
      if (projections.symbolTable(symbol).length() == 0) return false;
    }
    return true;
  }

  private static boolean conjunctive(SqlBooleanPredicateProgram where, int node) {
    int operator = where.booleanOperator(node);
    return operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF
        || operator == SqlBooleanPredicateProgram.BOOLEAN_AND
            && conjunctive(where, where.booleanLeft(node))
            && conjunctive(where, where.booleanRight(node));
  }
}
