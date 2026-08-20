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
      BoundSqlStatement bound) {
    bound.joinOuterColumn = -1;
    bound.joinInnerColumn = -1;
    bestScore = -1;
    if (program.available()) collect(source, program, program.root(), bound);
  }

  private void collect(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram program,
      int node,
      BoundSqlStatement bound) {
    int operator = program.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND) {
      collect(source, program, program.booleanLeft(node), bound);
      collect(source, program, program.booleanRight(node), bound);
    } else if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      candidate(source, program, program.booleanLeft(node), bound);
    }
  }

  private void candidate(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram program,
      int leaf,
      BoundSqlStatement bound) {
    if (source.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON
        || source.comparison(leaf) != SqlComparison.EQUAL) return;
    int left = SqlBooleanPredicateProgram.PROGRAM_LEFT;
    int right = SqlBooleanPredicateProgram.PROGRAM_RIGHT;
    int leftColumn = program.rawColumn(leaf, left);
    int rightColumn = program.rawColumn(leaf, right);
    if (leftColumn < 0 || rightColumn < 0) return;
    int leftScope = program.scope(leaf, left, 0);
    int rightScope = program.scope(leaf, right, 0);
    if (leftScope == rightScope) return;
    int outer = leftScope == SqlBoundBooleanPredicateProgram.SCOPE_LEFT
        ? leftColumn : rightColumn;
    int inner = leftScope == SqlBoundBooleanPredicateProgram.SCOPE_RIGHT
        ? leftColumn : rightColumn;
    int outerDescriptor = bound.table.typeDescriptor(outer);
    int innerDescriptor = bound.joinTable.typeDescriptor(inner);
    if (SqlTypeDescriptor.comparisonFamily(outerDescriptor)
            != SqlTypeDescriptor.comparisonFamily(innerDescriptor)
        || outerDescriptor != innerDescriptor
            && SqlTypeDescriptor.comparisonFamily(innerDescriptor)
                == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC
        || SqlTypeDescriptor.typeId(innerDescriptor)
            == SqlTypeDescriptor.TYPE_ID_VARCHAR) return;
    int score = inner == 0 || bound.joinTable.hasUniqueIndexOn(inner)
        ? 2 : bound.joinTable.hasIndexOn(inner) ? 1 : 0;
    if (score > bestScore) {
      bound.joinOuterColumn = outer;
      bound.joinInnerColumn = inner;
      bestScore = score;
    }
  }
}
