package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Validates typed Boolean predicate leaves after inference has completed. */
final class SqlBooleanPredicateValidator {
  private SqlBooleanPredicateValidator() {
  }

  static StatusCode validate(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      int leaf,
      int test) {
    int left = target.resultDescriptor(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT);
    if (test == SqlBooleanPredicateProgram.TEST_NULL) {
      return StatusCode.OK;
    }
    if (test == SqlBooleanPredicateProgram.TEST_TRUTH
        || test == SqlBooleanPredicateProgram.TEST_BOOLEAN) {
      return SqlTypeDescriptor.typeId(left) == SqlTypeDescriptor.TYPE_ID_BOOLEAN
          ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
    }
    if (test == SqlBooleanPredicateProgram.TEST_COMPARISON) {
      return comparable(
          left,
          target.resultDescriptor(leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT),
          source.comparison(leaf));
    }
    if (test == SqlBooleanPredicateProgram.TEST_BETWEEN) {
      StatusCode status = comparable(
          left,
          target.resultDescriptor(leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER),
          SqlComparison.GREATER_OR_EQUAL);
      return status.isOk()
          ? comparable(
              left,
              target.resultDescriptor(leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER),
              SqlComparison.LESS_OR_EQUAL)
          : status;
    }
    return test == SqlBooleanPredicateProgram.TEST_MEMBERSHIP
        ? membership(target, leaf, left) : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static StatusCode membership(
      SqlBoundBooleanPredicateProgram target, int leaf, int left) {
    for (int member = 0; member < target.memberCount(leaf); member++) {
      int descriptor = target.memberDescriptor(leaf, member);
      if (descriptor != 0 && !SqlTypeDescriptor.canCompare(left, descriptor)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
    }
    return StatusCode.OK;
  }

  private static StatusCode comparable(
      int left, int right, SqlComparison comparison) {
    if (!SqlTypeDescriptor.canCompare(left, right)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (SqlTypeDescriptor.typeId(left) == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        && comparison != SqlComparison.EQUAL
        && comparison != SqlComparison.NOT_EQUAL) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return StatusCode.OK;
  }
}
