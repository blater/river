package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Transitive output-lane liveness for composed block projections. */
final class SqlBlockProjectionLiveness {
  private final boolean[][] live =
      new boolean[io.riverdb.sql.SqlQuery.MAXIMUM_QUERY_BLOCKS]
          [SqlCommand.MAXIMUM_PROJECTIONS];
  private SqlCommand[] commands;

  void prepare(SqlCommand[] commands, SqlBlockSchema[] schemas, int count) {
    if (count < 1) return;
    this.commands = commands;
    java.util.Arrays.fill(live[0], 0, schemas[0].count(), true);
    for (int block = 1; block < count; block++) {
      SqlCommand parent = commands[block - 1];
      if (!markReferenced(parent, schemas[block], live[block], live[block - 1])) {
        java.util.Arrays.fill(live[block], 0, schemas[block].count(), true);
      }
      markOrder(commands[block], schemas[block], live[block]);
    }
  }

  boolean live(int block, int projection, SqlBlockSchema[] schemas, int count) {
    if (block < 0 || block >= count || projection < 0
        || projection >= SqlCommand.MAXIMUM_PROJECTIONS) return false;
    SqlCommand command = commands[block];
    if (command.aggregateInvocationCount() > 0
        || command.type() == io.riverdb.sql.SqlCommandType.DISTINCT_SCAN) return true;
    return projection < schemas[block].count() && live[block][projection];
  }

  void reset(int count) {
    for (int block = 0; block < count; block++) {
      java.util.Arrays.fill(live[block], false);
    }
    commands = null;
  }

  private static boolean markReferenced(
      SqlCommand command, SqlBlockSchema child, boolean[] live, boolean[] parentLive) {
    if (command == null || command.isSelectAll()
        || command.type() != io.riverdb.sql.SqlCommandType.SELECT
            && command.type() != io.riverdb.sql.SqlCommandType.SCAN) return false;
    for (int projection = 0; projection < command.columnCount(); projection++) {
      if (parentLive[projection]
          && !markExpression(command, command.projectionExpression(projection), child, live)) {
        return false;
      }
    }
    SqlBooleanPredicateProgram where = command.wherePredicates();
    for (int leaf = 0; leaf < where.leafCount(); leaf++) {
      for (int program = SqlBooleanPredicateProgram.PROGRAM_LEFT;
          program <= SqlBooleanPredicateProgram.PROGRAM_UPPER; program++) {
        for (int node = 0; node < where.programNodeCount(leaf, program); node++) {
          if (where.programOperator(leaf, program, node) != SqlScalarExpression.COLUMN) continue;
          int symbol = (int) where.programOperand(leaf, program, node);
          if (!mark(child, command.predicateSymbolName(symbol), live)) return false;
        }
      }
    }
    markOrder(command, child, live);
    return true;
  }

  private static boolean markExpression(
      SqlCommand command,
      SqlScalarExpression expression,
      SqlBlockSchema child,
      boolean[] live) {
    if (expression == null || !expression.isAvailable()) return false;
    for (int node = 0; node < expression.nodeCount(); node++) {
      if (expression.operator(node) != SqlScalarExpression.COLUMN) continue;
      int symbol = (int) expression.operand(node);
      if (!mark(child, command.projectionSymbolName(symbol), live)) return false;
    }
    return true;
  }

  private static void markOrder(
      SqlCommand command, SqlBlockSchema schema, boolean[] live) {
    if (command == null) return;
    for (int order = 0; order < command.orderExpressionCount(); order++) {
      int column = command.orderColumnTableName(order).length() > 0
          ? SqlProjectionBinder.resolveOrderProjection(command, order)
          : schema.find(command.orderColumnName(order));
      if (column >= 0) live[column] = true;
    }
  }

  private static boolean mark(
      SqlBlockSchema schema, CharSequence name, boolean[] live) {
    int column = schema.find(name);
    if (column < 0) return false;
    live[column] = true;
    return true;
  }
}
