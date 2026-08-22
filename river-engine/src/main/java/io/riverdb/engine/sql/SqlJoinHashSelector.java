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
      SqlBoundJoinContext context,
      int stage) {
    if (selected || context.hasPhysicalStrategy()
        || !SqlJoinPredicateClassifier.total(source)) return;
    collect(source, program, program.root(), context, stage);
  }

  private void collect(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram program,
      int node,
      SqlBoundJoinContext context,
      int stage) {
    if (selected) return;
    int operator = program.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND) {
      collect(source, program, program.booleanLeft(node), context, stage);
      collect(source, program, program.booleanRight(node), context, stage);
    } else if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      candidate(source, program, program.booleanLeft(node), context, stage);
    }
  }

  private void candidate(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram program,
      int leaf,
      SqlBoundJoinContext context,
      int stage) {
    if (source.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON
        || source.comparison(leaf) != SqlComparison.EQUAL) return;
    int left = SqlBooleanPredicateProgram.PROGRAM_LEFT;
    int right = SqlBooleanPredicateProgram.PROGRAM_RIGHT;
    int leftColumn = program.rawColumn(leaf, left);
    int rightColumn = program.rawColumn(leaf, right);
    if (leftColumn < 0 || rightColumn < 0) return;
    int leftRole = context.localRole(program.scope(leaf, left, 0));
    int rightRole = context.localRole(program.scope(leaf, right, 0));
    int current = stage + 1;
    if (leftRole == rightRole
        || leftRole != current && rightRole != current) return;
    int outerRole = leftRole == current ? rightRole : leftRole;
    if (outerRole < 0 || outerRole >= current) return;
    int outerColumn = leftRole == current ? rightColumn : leftColumn;
    int innerColumn = leftRole == current ? leftColumn : rightColumn;
    int outerDescriptor = context.table(outerRole).typeDescriptor(outerColumn);
    int innerDescriptor = context.table(current).typeDescriptor(innerColumn);
    if (!SqlTypeDescriptor.canCompare(outerDescriptor, innerDescriptor)
        || indexed(context, stage)) return;
    context.setStrategy(
        stage, SqlJoinStrategy.HASH, outerRole, outerColumn, innerColumn);
    selected = true;
  }

  private static boolean indexed(SqlBoundJoinContext context, int stage) {
    int column = context.accessInnerColumn(stage);
    return column >= 0
        && (column == 0 || context.table(stage + 1).hasIndexOn(column));
  }

}
