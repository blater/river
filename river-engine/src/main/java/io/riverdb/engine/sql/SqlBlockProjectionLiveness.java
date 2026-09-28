package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlScalarExpression;
import java.util.Arrays;

/** Transitive output-lane and physical-column liveness for bound blocks. */
final class SqlBlockProjectionLiveness {
  private final boolean[][] live =
      new boolean[io.riverdb.sql.SqlQuery.MAXIMUM_QUERY_BLOCKS]
          [SqlCommand.MAXIMUM_PROJECTIONS];
  private SqlCommand[] commands;
  private final boolean[] physical = new boolean[SqlCommand.MAXIMUM_COLUMNS];
  private int physicalColumns;
  private boolean prepared;

  void prepare(
      SqlCommand[] commands, SqlBlockSchema[] schemas,
      SqlBlockSchema base, int count) {
    if (count < 1) return;
    this.commands = commands;
    Arrays.fill(live[0], 0, schemas[0].count(), true);
    for (int block = 1; block < count; block++) {
      SqlCommand parent = commands[block - 1];
      if (!markReferenced(parent, schemas[block], live[block], live[block - 1])) {
        Arrays.fill(live[block], 0, schemas[block].count(), true);
      }
      markOrder(commands[block], schemas[block], live[block]);
    }
    physicalColumns = base.count();
    Arrays.fill(physical, 0, physicalColumns, false);
    if (!markReferenced(commands[count - 1], base, physical, live[count - 1])) {
      Arrays.fill(physical, 0, physicalColumns, true);
    }
    prepared = true;
  }

  boolean physicalLive(int column) {
    return !prepared || column < 0 || column >= physicalColumns || physical[column];
  }

  boolean live(int block, int projection, SqlBlockSchema[] schemas, int count) {
    if (block < 0 || block >= count || projection < 0
        || projection >= SqlCommand.MAXIMUM_PROJECTIONS) return false;
    SqlCommand command = commands[block];
    if (command.aggregates().invocationCount() > 0
        || command.type() == SqlCommandType.DISTINCT_SCAN) return true;
    return projection < schemas[block].count() && live[block][projection];
  }

  void reset(int count) {
    for (int block = 0; block < count; block++) {
      Arrays.fill(live[block], false);
    }
    commands = null;
    physicalColumns = 0;
    prepared = false;
  }

  private static boolean markReferenced(
      SqlCommand command, SqlBlockSchema child, boolean[] live, boolean[] parentLive) {
    if (command == null) return false;
    SqlCommandType type = command.type();
    if (command.aggregates().invocationCount() > 0) {
      for (int group = 0; group < command.grouping().count(); group++) {
        if (!markExpression(command, command.grouping().expression(group), child, live)) {
          return false;
        }
      }
      for (int invocation = 0; invocation < command.aggregates().invocationCount(); invocation++) {
        int operand = command.aggregates().operandProjection(invocation);
        if (operand >= 0 && !markExpression(
            command, command.aggregateOperandExpression(operand), child, live)) {
          return false;
        }
      }
    } else if (command.isSelectAll()) {
      for (int column = 0; column < child.count(); column++) {
        if (parentLive[column]) live[column] = true;
      }
    } else if (type == SqlCommandType.SELECT || type == SqlCommandType.SCAN
        || type == SqlCommandType.DISTINCT_SCAN) {
      for (int projection = 0; projection < command.columnCount(); projection++) {
        if ((parentLive[projection] || type == SqlCommandType.DISTINCT_SCAN)
            && !markExpression(command, command.projectionExpression(projection), child, live)) {
          return false;
        }
      }
    } else return false;
    SqlBooleanPredicateProgram where = command.wherePredicates();
    for (int leaf = 0; leaf < where.leafCount(); leaf++) {
      for (int program = SqlBooleanPredicateProgram.PROGRAM_LEFT;
          program <= SqlBooleanPredicateProgram.PROGRAM_UPPER; program++) {
        for (int node = 0; node < where.programNodeCount(leaf, program); node++) {
          if (where.programOperator(leaf, program, node) != SqlScalarExpression.COLUMN) continue;
          int symbol = (int) where.programOperand(leaf, program, node);
          if (!mark(child, command.projections().symbolName(symbol), live)) return false;
        }
      }
    }
    return markUnprojectedOrder(command, child, live);
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
      if (!mark(child, command.projections().symbolName(symbol), live)) return false;
    }
    return true;
  }

  private static void markOrder(
      SqlCommand command, SqlBlockSchema schema, boolean[] live) {
    if (command == null) return;
    for (int order = 0; order < command.orderBy().count(); order++) {
      int column = command.orderBy().qualifier(order).length() > 0
          ? SqlProjectionBinder.resolveOrderProjection(command, order)
          : schema.find(command.orderBy().name(order));
      if (column >= 0) live[column] = true;
    }
  }

  private static boolean markUnprojectedOrder(
      SqlCommand command, SqlBlockSchema child, boolean[] live) {
    for (int order = 0; order < command.orderBy().count(); order++) {
      int projection = SqlProjectionBinder.resolveOrderProjection(command, order);
      if (projection >= 0) continue;
      if (projection == -2) return false;
      int column = child.find(command.orderBy().name(order));
      if (column < 0) return false;
      live[column] = true;
    }
    return true;
  }

  private static boolean mark(
      SqlBlockSchema schema, CharSequence name, boolean[] live) {
    int column = schema.find(name);
    if (column < 0) return false;
    live[column] = true;
    return true;
  }
}
