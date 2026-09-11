package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Reusable composite sort layout for block operands and finalized outputs. */
final class SqlBlockOutputOrder {
  private final int[] columns = new int[SqlCommand.MAXIMUM_PROJECTIONS];
  private final boolean[] descending = new boolean[SqlCommand.MAXIMUM_PROJECTIONS];

  StatusCode beginOutput(
      SqlCommand command, SqlBlockSchema schema, SqlBlockRowStore output) {
    if (!(command.orderBy().count() > 0)) return output.begin(schema, -1, false);
    int count = appendOrder(command, schema);
    return count < 0 ? StatusCode.INVALID_EXTERNAL_INPUT
        : output.begin(schema, columns, descending, count);
  }

  StatusCode beginOperands(
      SqlCommand command, SqlBlockSchema schema, SqlBlockRowStore output) {
    if (command.grouping().count() > 0) {
      return beginGroups(command, schema, output);
    }
    if (command.type() == SqlCommandType.DISTINCT_SCAN) {
      return beginDistinct(command, schema, output);
    }
    return command.aggregates().invocationCount() == 0
        ? beginOutput(command, schema, output) : output.begin(schema, -1, false);
  }

  private StatusCode beginGroups(
      SqlCommand command, SqlBlockSchema schema, SqlBlockRowStore output) {
    int count = command.grouping().count();
    for (int index = 0; index < count; index++) {
      columns[index] = index;
      descending[index] = false;
    }
    return output.begin(schema, columns, descending, count);
  }

  private StatusCode beginDistinct(
      SqlCommand command, SqlBlockSchema schema, SqlBlockRowStore output) {
    int count = (command.orderBy().count() > 0) ? appendOrder(command, schema) : 0;
    if (count < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int column = 0; column < schema.count(); column++) {
      if (!contains(count, column)) {
        columns[count] = column;
        descending[count++] = false;
      }
    }
    return output.begin(schema, columns, descending, count);
  }

  private int appendOrder(SqlCommand command, SqlBlockSchema schema) {
    int count = command.orderBy().count();
    for (int expression = 0; expression < count; expression++) {
      columns[expression] = projection(command, schema, expression);
      descending[expression] = command.orderBy().descending(expression);
      if (columns[expression] < 0 || contains(expression, columns[expression])) return -1;
    }
    return count;
  }

  private boolean contains(int count, int candidate) {
    for (int index = 0; index < count; index++) {
      if (columns[index] == candidate) return true;
    }
    return false;
  }

  private static int projection(
      SqlCommand command, SqlBlockSchema schema, int expression) {
    if (command.orderBy().qualifier(expression).length() > 0) {
      int projection = SqlProjectionBinder.resolveOrderProjection(command, expression);
      return projection < schema.count() ? projection : -1;
    }
    int order = schema.find(command.orderBy().name(expression));
    if (order >= 0) return order;
    for (int projection = 0; projection < command.columnCount(); projection++) {
      if (SqlBindingNames.same(
          command.columnName(projection), command.orderBy().name(expression))) {
        return projection;
      }
    }
    return -1;
  }

}
