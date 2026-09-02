package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;

/** Recognizes an AND-only parser predicate tree. */
final class SqlPredicateConjunction {
  private SqlPredicateConjunction() { }

  static boolean only(SqlBooleanPredicateProgram program, int node) {
    int operator = program.booleanOperator(node);
    return operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF
        || operator == SqlBooleanPredicateProgram.BOOLEAN_AND
            && only(program, program.booleanLeft(node))
            && only(program, program.booleanRight(node));
  }
}
