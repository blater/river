package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Conservative proof that join-order changes cannot expose row-time expression errors. */
final class SqlJoinPredicateClassifier {
  private SqlJoinPredicateClassifier() {
  }

  static boolean total(SqlBooleanPredicateProgram source) {
    for (int leaf = 0; leaf < source.leafCount(); leaf++) {
      int test = source.leafTest(leaf);
      if (!simple(source, leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT)) return false;
      if (test == SqlBooleanPredicateProgram.TEST_COMPARISON
          && !simple(source, leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT)
          || test == SqlBooleanPredicateProgram.TEST_BETWEEN
              && (!simple(source, leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER)
                  || !simple(source, leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER))) {
        return false;
      }
      if (test < SqlBooleanPredicateProgram.TEST_COMPARISON
          || test > SqlBooleanPredicateProgram.TEST_BOOLEAN) return false;
    }
    return true;
  }

  static boolean totalJoinOrder(SqlCommand command) {
    if (!total(command.wherePredicates())) return false;
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      if (!total(command.joinChain().onPredicates(stage))) return false;
    }
    for (int projection = 0; projection < command.columnCount(); projection++) {
      if (!simple(command.projectionExpression(projection))) return false;
    }
    return true;
  }

  private static boolean simple(
      SqlBooleanPredicateProgram source, int leaf, int program) {
    if (source.programNodeCount(leaf, program) != 1) return false;
    return simple(source.programOperator(leaf, program, 0));
  }

  private static boolean simple(SqlScalarExpression expression) {
    return expression != null && expression.nodeCount() == 1
        && simple(expression.operator(0));
  }

  private static boolean simple(int operator) {
    return operator == SqlScalarExpression.COLUMN
        || operator == SqlScalarExpression.LITERAL
        || operator == SqlScalarExpression.NULL;
  }
}
