package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Selects one conservative total raw-equality HASH stage before cost planning. */
final class SqlJoinHashSelector {
  private boolean selected;

  void begin() { selected = false; }

  void select(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram program,
      BoundSqlStatement bound,
      int stage) {
    if (selected || !total(source)) return;
    collect(source, program, program.root(), bound, stage);
  }

  private void collect(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram program,
      int node,
      BoundSqlStatement bound,
      int stage) {
    if (selected) return;
    int operator = program.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND) {
      collect(source, program, program.booleanLeft(node), bound, stage);
      collect(source, program, program.booleanRight(node), bound, stage);
    } else if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      candidate(source, program, program.booleanLeft(node), bound, stage);
    }
  }

  private void candidate(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram program,
      int leaf,
      BoundSqlStatement bound,
      int stage) {
    if (source.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON
        || source.comparison(leaf) != SqlComparison.EQUAL) return;
    int left = SqlBooleanPredicateProgram.PROGRAM_LEFT;
    int right = SqlBooleanPredicateProgram.PROGRAM_RIGHT;
    int leftColumn = program.rawColumn(leaf, left);
    int rightColumn = program.rawColumn(leaf, right);
    if (leftColumn < 0 || rightColumn < 0) return;
    int leftRole = program.scope(leaf, left, 0);
    int rightRole = program.scope(leaf, right, 0);
    int current = stage + 1;
    if (leftRole == rightRole
        || leftRole != current && rightRole != current) return;
    int outerRole = leftRole == current ? rightRole : leftRole;
    if (outerRole < 0 || outerRole >= current) return;
    int outerColumn = leftRole == current ? rightColumn : leftColumn;
    int innerColumn = leftRole == current ? leftColumn : rightColumn;
    int outerDescriptor = bound.joinRole(outerRole).typeDescriptor(outerColumn);
    int innerDescriptor = bound.joinRole(current).typeDescriptor(innerColumn);
    if (!SqlTypeDescriptor.canCompare(outerDescriptor, innerDescriptor)
        || indexed(bound, stage)) return;
    bound.setJoinHash(stage, outerRole, outerColumn, innerColumn);
    selected = true;
  }

  private static boolean indexed(BoundSqlStatement bound, int stage) {
    int column = bound.joinAccessInnerColumn(stage);
    return column >= 0
        && (column == 0 || bound.joinRole(stage + 1).hasIndexOn(column));
  }

  private static boolean total(SqlBooleanPredicateProgram source) {
    for (int leaf = 0; leaf < source.leafCount(); leaf++) {
      int test = source.leafTest(leaf);
      if (!simple(source, leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT)) {
        return false;
      }
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

  private static boolean simple(
      SqlBooleanPredicateProgram source, int leaf, int program) {
    if (source.programNodeCount(leaf, program) != 1) return false;
    int operator = source.programOperator(leaf, program, 0);
    return operator == SqlScalarExpression.COLUMN
        || operator == SqlScalarExpression.LITERAL
        || operator == SqlScalarExpression.NULL;
  }
}
