package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlScalarExpression;

/** Selects the common block evaluator for descriptor-backed row expressions. */
final class SqlDescriptorExpressionRouting {
  private SqlDescriptorExpressionRouting() { }

  static boolean required(SqlCommand command) {
    if (command == null || command.isSelectAll()
        || command.type() != SqlCommandType.SCAN
            && command.type() != SqlCommandType.SELECT) return false;
    for (int projection = 0; projection < command.columnCount(); projection++) {
      SqlScalarExpression expression = command.projectionExpression(projection);
      if (expression != null && expression.isAvailable()
          && !expression.isDirectColumnReference()
          && !expression.isNullLiteral()) return true;
    }
    return false;
  }

  static boolean mutationPredicateRequired(SqlCommand command) {
    if (command == null
        || command.type() != SqlCommandType.UPDATE
            && command.type() != SqlCommandType.DELETE) return false;
    return predicateRequired(command);
  }

  static boolean predicateRequired(SqlCommand command) {
    if (command == null) return false;
    SqlBooleanPredicateProgram predicates = command.wherePredicates();
    for (int leaf = 0; leaf < predicates.leafCount(); leaf++) {
      for (int program = SqlBooleanPredicateProgram.PROGRAM_LEFT;
          program <= SqlBooleanPredicateProgram.PROGRAM_UPPER; program++) {
        int count = predicates.programNodeCount(leaf, program);
        if (count > 1) return true;
        if (count == 1) {
          int operator = predicates.programOperator(leaf, program, 0);
          if (operator != SqlScalarExpression.COLUMN
              && operator != SqlScalarExpression.LITERAL
              && operator != SqlScalarExpression.NULL) return true;
        }
      }
    }
    return false;
  }
}
