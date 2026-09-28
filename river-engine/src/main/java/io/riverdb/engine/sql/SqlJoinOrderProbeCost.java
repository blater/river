package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Estimates one indexed JOIN probe or an inner scan per accepted root row. */
final class SqlJoinOrderProbeCost {
  private final SqlJoinRoleResolver symbols = new SqlJoinRoleResolver();

  double perRow(SqlCommand command, SqlBoundJoinContext context, int inner) {
    SqlBooleanPredicateProgram on = command.joinChain().onPredicates(0);
    TableDefinition table = context.table(inner);
    for (int leaf = 0; leaf < on.leafCount(); leaf++) {
      if (on.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON
          || on.comparison(leaf) != SqlComparison.EQUAL
          || on.programNodeCount(leaf, 0) != 1
          || on.programNodeCount(leaf, 1) != 1
          || on.programOperator(leaf, 0, 0) != SqlScalarExpression.COLUMN
          || on.programOperator(leaf, 1, 0) != SqlScalarExpression.COLUMN) continue;
      int left = column(command, context, on, leaf, 0, inner);
      int right = column(command, context, on, leaf, 1, inner);
      int outerLeft = column(command, context, on, leaf, 0, 1 - inner);
      int outerRight = column(command, context, on, leaf, 1, 1 - inner);
      if ((left >= 0 && outerRight >= 0 && indexed(table, left))
          || (right >= 0 && outerLeft >= 0 && indexed(table, right))) {
        return SqlTwoRoleJoinOrder.treeDepth(context.statistics(inner).rowCount());
      }
    }
    return context.statistics(inner).rowCount();
  }

  private int column(
      SqlCommand command, SqlBoundJoinContext context,
      SqlBooleanPredicateProgram on, int leaf, int side, int role) {
    int symbol = (int) on.programOperand(leaf, side, 0);
    return symbols.resolve(command, context, symbol, 2) && symbols.role() == role
        ? symbols.column() : -1;
  }

  private static boolean indexed(TableDefinition table, int column) {
    return table.hasPrimaryIndexOn(column) || table.hasIndexOn(column);
  }
}
