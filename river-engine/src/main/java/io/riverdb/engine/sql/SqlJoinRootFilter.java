package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Mandatory, root-local WHERE leaves safe to test before JOIN probes. */
final class SqlJoinRootFilter {
  private int[] leaves = new int[0];
  private int count;

  StatusCode configure(
      SqlCommand command,
      SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where) {
    count = 0;
    if (!where.available()
        || !SqlJoinPredicateClassifier.total(command.wherePredicates())
        || !SqlJoinPredicateClassifier.total(where)) {
      return StatusCode.OK;
    }
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      if (!SqlJoinPredicateClassifier.total(command.joinChain().onPredicates(stage))
          || !SqlJoinPredicateClassifier.total(context.onBoolean(stage))) {
        return StatusCode.OK;
      }
    }
    if (leaves.length < where.leafCount()) {
      try {
        leaves = new int[where.leafCount()];
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    collect(where, context, where.root());
    return StatusCode.OK;
  }

  private void collect(
      SqlBoundBooleanPredicateProgram where,
      SqlBoundJoinContext context,
      int node) {
    int operator = where.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND) {
      collect(where, context, where.booleanLeft(node));
      collect(where, context, where.booleanRight(node));
    } else if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      int leaf = where.booleanLeft(node);
      if (where.leafTest(leaf) == SqlBooleanPredicateProgram.TEST_COMPARISON
          && rootOperand(where, context, leaf,
              SqlBooleanPredicateProgram.PROGRAM_LEFT)
          && rootOperand(where, context, leaf,
              SqlBooleanPredicateProgram.PROGRAM_RIGHT)
          && (where.operator(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0)
                  == SqlScalarExpression.COLUMN
              || where.operator(leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0)
                  == SqlScalarExpression.COLUMN)) {
        leaves[count++] = leaf;
      }
    }
  }

  private static boolean rootOperand(
      SqlBoundBooleanPredicateProgram where,
      SqlBoundJoinContext context,
      int leaf,
      int program) {
    return where.nodeCount(leaf, program) == 1
        && (where.operator(leaf, program, 0) != SqlScalarExpression.COLUMN
            || context.localRole(where.scope(leaf, program, 0)) == 0);
  }

  int[] leaves() { return leaves; }
  int count() { return count; }
}
