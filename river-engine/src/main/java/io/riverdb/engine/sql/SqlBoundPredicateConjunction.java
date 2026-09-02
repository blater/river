package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;

/** Recognizes the AND-only shape required for safe direct index bounds. */
final class SqlBoundPredicateConjunction {
  private SqlBoundPredicateConjunction() { }

  static boolean only(SqlBoundBooleanPredicateProgram program, int node) {
    int operator = program.booleanOperator(node);
    return operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF
        || operator == SqlBooleanPredicateProgram.BOOLEAN_AND
            && only(program, program.booleanLeft(node))
            && only(program, program.booleanRight(node));
  }
}
