package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;

/** Coordinates untyped NULL inference and Boolean operand validation. */
final class SqlBooleanPredicateTypes {
  private SqlBooleanPredicateTypes() {
  }

  static StatusCode validate(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      int leaf) {
    int test = source.leafTest(leaf);
    if (test == SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS) {
      return StatusCode.OK;
    }
    if (test == SqlBooleanPredicateProgram.TEST_SUBQUERY_COMPARISON
        || test == SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP) {
      return target.resultDescriptor(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT) != 0
          ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
    }
    SqlBooleanPredicateInference.infer(target, leaf, test);
    return SqlBooleanPredicateValidator.validate(source, target, leaf, test);
  }
}
