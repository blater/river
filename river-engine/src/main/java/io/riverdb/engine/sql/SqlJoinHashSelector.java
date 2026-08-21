package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Selects one conservative total raw-equality HASH stage before cost planning. */
final class SqlJoinHashSelector {
  private boolean selected;

  void begin() { selected = false; }

  void select(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram program,
      BoundSqlStatement bound,
      int stage) {
    if (selected || bound.hasPhysicalJoinStrategy()
        || !SqlJoinPredicateClassifier.total(source)) return;
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
    bound.setJoinStrategy(
        stage, SqlJoinStrategy.HASH, outerRole, outerColumn, innerColumn);
    selected = true;
  }

  private static boolean indexed(BoundSqlStatement bound, int stage) {
    int column = bound.joinAccessInnerColumn(stage);
    return column >= 0
        && (column == 0 || bound.joinRole(stage + 1).hasIndexOn(column));
  }

}
