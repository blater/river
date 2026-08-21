package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Selects one mandatory raw cross-scope equality for inner JOIN access. */
final class SqlJoinAccessSelector {
  private int bestScore;

  void select(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram program,
      BoundSqlStatement bound,
      int stage) {
    bestScore = -1;
    if (program.available()) collect(source, program, program.root(), bound, stage);
  }

  private void collect(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram program,
      int node,
      BoundSqlStatement bound,
      int stage) {
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
    int leftScope = program.scope(leaf, left, 0);
    int rightScope = program.scope(leaf, right, 0);
    int rightRole = stage + 1;
    if (leftScope == rightScope
        || leftScope != rightRole && rightScope != rightRole) return;
    int outerRole = leftScope == rightRole ? rightScope : leftScope;
    if (outerRole < 0 || outerRole >= rightRole) return;
    int outer = leftScope == rightRole ? rightColumn : leftColumn;
    int inner = leftScope == rightRole ? leftColumn : rightColumn;
    int outerDescriptor = bound.joinRole(outerRole).typeDescriptor(outer);
    int innerDescriptor = bound.joinRole(rightRole).typeDescriptor(inner);
    if (SqlTypeDescriptor.comparisonFamily(outerDescriptor)
            != SqlTypeDescriptor.comparisonFamily(innerDescriptor)
        || outerDescriptor != innerDescriptor
            && SqlTypeDescriptor.comparisonFamily(innerDescriptor)
                == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC
        || SqlTypeDescriptor.typeId(innerDescriptor)
            == SqlTypeDescriptor.TYPE_ID_VARCHAR) return;
    int score = inner == 0 || bound.joinRole(rightRole).hasUniqueIndexOn(inner)
        ? 2 : bound.joinRole(rightRole).hasIndexOn(inner) ? 1 : 0;
    if (score > bestScore) {
      bound.setJoinAccess(stage, outerRole, outer, inner);
      bestScore = score;
    }
  }
}
