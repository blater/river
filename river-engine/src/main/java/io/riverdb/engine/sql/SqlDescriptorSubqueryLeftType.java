package io.riverdb.engine.sql;

import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves the typed left scalar consumed by a descriptor subquery edge. */
final class SqlDescriptorSubqueryLeftType {
  private SqlDescriptorSubqueryLeftType() { }

  static int resolve(
      SqlBooleanPredicateProgram program, int leaf,
      SqlCommand command, TableDescriptor table) {
    int side = SqlBooleanPredicateProgram.PROGRAM_LEFT;
    int count = program.programNodeCount(leaf, side);
    if (count <= 0) return 0;
    int descriptor = program.programDescriptor(leaf, side, count - 1);
    if (descriptor != 0) return descriptor;
    if (count != 1
        || program.programOperator(leaf, side, 0) != SqlScalarExpression.COLUMN) return 0;
    int symbol = (int) program.programOperand(leaf, side, 0);
    int column = table.findColumn(command.predicateSymbolName(symbol));
    return column < 0 ? 0 : table.typeDescriptorAt(column);
  }
}
