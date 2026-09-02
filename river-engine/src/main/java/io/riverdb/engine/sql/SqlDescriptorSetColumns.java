package io.riverdb.engine.sql;

import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves direct descriptor columns used by set-operation expressions. */
final class SqlDescriptorSetColumns {
  private SqlDescriptorSetColumns() { }

  static int projection(SqlCommand command, TableDescriptor table, int projection) {
    return symbol(command, table, command.directProjectionSymbol(projection));
  }

  static int expression(
      SqlCommand command, TableDescriptor table, SqlScalarExpression expression) {
    return expression != null && expression.isDirectColumnReference()
        ? symbol(command, table, (int) expression.operand(0)) : -1;
  }

  static boolean named(
      SqlCommand command, SqlScalarExpression expression, CharSequence name) {
    if (expression == null || !expression.isDirectColumnReference()) return false;
    int symbol = (int) expression.operand(0);
    return SqlDescriptorPrimaryPredicate.same(command.projectionSymbolName(symbol), name);
  }

  private static int symbol(SqlCommand command, TableDescriptor table, int symbol) {
    if (symbol < 0) return -1;
    CharSequence qualifier = command.projectionSymbolTable(symbol);
    if (qualifier.length() != 0
        && !SqlDescriptorPrimaryPredicate.same(qualifier, command.tableName())
        && !(command.tableAlias().length() > 0
            && SqlDescriptorPrimaryPredicate.same(qualifier, command.tableAlias()))) return -1;
    return table.findColumn(command.projectionSymbolName(symbol));
  }
}
