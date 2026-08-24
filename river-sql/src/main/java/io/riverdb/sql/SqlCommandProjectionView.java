package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Projection symbols, expressions, and null markers owned by a parsed command. */
final class SqlCommandProjectionView {
  private SqlCommandProjectionView() { }

  static SqlScalarExpression expression(SqlCommand command, int index) {
    return index >= 0 && index < command.columnCount
        ? command.projections.expression(index) : null;
  }

  static SqlScalarExpression aggregateExpression(SqlCommand command, int index) {
    return index >= 0 && index < SqlCommand.MAXIMUM_COLUMNS
        ? command.projections.expression(index) : null;
  }

  static int register(SqlCommand command, CharSequence table, CharSequence name) {
    return command.projections.registerSymbol(table, name);
  }

  static void adoptName(SqlCommand command, int index) {
    SqlScalarExpression expression = expression(command, index);
    int symbol = expression != null && expression.isDirectColumnReference()
        ? (int) expression.operand(0) : -1;
    SqlIdentifier name = command.projections.symbolName(symbol);
    SqlIdentifier table = command.projections.symbolTable(symbol);
    if (name != null && table != null && index >= 0 && index < command.columnCount) {
      command.columnNames[index].copyFrom(name);
      command.columnTableNames[index].copyFrom(table);
    }
  }

  static StatusCode setColumn(
      SqlCommand command,
      int index,
      CharSequence table,
      CharSequence name) {
    SqlScalarExpression expression = expression(command, index);
    int symbol = register(command, table, name);
    if (expression == null || symbol < 0) return StatusCode.RESOURCE_EXHAUSTED;
    expression.reset();
    if (!expression.append(SqlScalarExpression.COLUMN, symbol, 0)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expression.finishUnresolved();
    adoptName(command, index);
    return StatusCode.OK;
  }

  static StatusCode setNull(SqlCommand command, int index) {
    SqlScalarExpression expression = expression(command, index);
    if (expression == null) return StatusCode.RESOURCE_EXHAUSTED;
    expression.reset();
    if (!expression.append(SqlScalarExpression.NULL, 0, 0)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expression.finishUnresolved();
    markNull(command);
    return StatusCode.OK;
  }

  static void markNull(SqlCommand command) {
    if (command.columnCount > 0) {
      command.nullProjections[command.columnCount - 1] = true;
    }
  }

  static int symbolCount(SqlCommand command) { return command.projections.symbolCount(); }
  static SqlIdentifier symbolTable(SqlCommand command, int index) {
    return command.projections.symbolTable(index);
  }
  static SqlIdentifier symbolName(SqlCommand command, int index) {
    return command.projections.symbolName(index);
  }
  static int directSymbol(SqlCommand command, int index) {
    SqlScalarExpression expression = expression(command, index);
    return expression != null && expression.isDirectColumnReference()
        ? (int) expression.operand(0) : -1;
  }
  static boolean isNull(SqlCommand command, int index) {
    return index >= 0 && index < command.columnCount && command.nullProjections[index];
  }
}
