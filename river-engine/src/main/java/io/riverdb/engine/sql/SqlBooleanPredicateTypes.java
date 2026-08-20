package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Resolves untyped NULL families and validates Boolean leaf operand types. */
final class SqlBooleanPredicateTypes {
  private SqlBooleanPredicateTypes() {
  }

  static StatusCode validate(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      int leaf) {
    int test = source.leafTest(leaf);
    infer(target, leaf, test);
    int left = target.resultDescriptor(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT);
    if (test == SqlBooleanPredicateProgram.TEST_NULL) return StatusCode.OK;
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

  private static void infer(
      SqlBoundBooleanPredicateProgram target, int leaf, int test) {
    int left = SqlBooleanPredicateProgram.PROGRAM_LEFT;
    if (test == SqlBooleanPredicateProgram.TEST_NULL) {
      resolve(target, leaf, left, SqlTypeDescriptor.BIGINT);
    } else if (test == SqlBooleanPredicateProgram.TEST_TRUTH
        || test == SqlBooleanPredicateProgram.TEST_BOOLEAN) {
      resolve(target, leaf, left, SqlTypeDescriptor.BOOLEAN);
    } else if (test == SqlBooleanPredicateProgram.TEST_COMPARISON) {
      inferPair(target, leaf, left, SqlBooleanPredicateProgram.PROGRAM_RIGHT);
    } else if (test == SqlBooleanPredicateProgram.TEST_BETWEEN) {
      inferRange(target, leaf);
    } else if (test == SqlBooleanPredicateProgram.TEST_MEMBERSHIP
        && target.unresolved(leaf, left)) {
      resolve(target, leaf, left, membershipDescriptor(target, leaf));
    }
  }

  private static int membershipDescriptor(
      SqlBoundBooleanPredicateProgram target, int leaf) {
    for (int member = 0; member < target.memberCount(leaf); member++) {
      if (target.memberDescriptor(leaf, member) != 0) {
        return target.memberDescriptor(leaf, member);
      }
    }
    return SqlTypeDescriptor.BIGINT;
  }

  private static void inferRange(
      SqlBoundBooleanPredicateProgram target, int leaf) {
    int descriptor = knownRangeDescriptor(target, leaf);
    resolve(target, leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, descriptor);
    resolve(target, leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER, descriptor);
    resolve(target, leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER, descriptor);
  }

  private static int knownRangeDescriptor(
      SqlBoundBooleanPredicateProgram target, int leaf) {
    int left = SqlBooleanPredicateProgram.PROGRAM_LEFT;
    int lower = SqlBooleanPredicateProgram.PROGRAM_LOWER;
    int upper = SqlBooleanPredicateProgram.PROGRAM_UPPER;
    if (!target.unresolved(leaf, left)) return target.resultDescriptor(leaf, left);
    if (!target.unresolved(leaf, lower)) return target.resultDescriptor(leaf, lower);
    return !target.unresolved(leaf, upper)
        ? target.resultDescriptor(leaf, upper) : SqlTypeDescriptor.BIGINT;
  }

  private static void inferPair(
      SqlBoundBooleanPredicateProgram target,
      int leaf,
      int left,
      int right) {
    boolean leftUnknown = target.unresolved(leaf, left);
    boolean rightUnknown = target.unresolved(leaf, right);
    if (leftUnknown && !rightUnknown) {
      resolve(target, leaf, left, target.resultDescriptor(leaf, right));
    } else if (!leftUnknown && rightUnknown) {
      resolve(target, leaf, right, target.resultDescriptor(leaf, left));
    } else if (leftUnknown) {
      resolve(target, leaf, left, SqlTypeDescriptor.BIGINT);
      resolve(target, leaf, right, SqlTypeDescriptor.BIGINT);
    }
  }

  private static void resolve(
      SqlBoundBooleanPredicateProgram target,
      int leaf,
      int program,
      int descriptor) {
    if (target.unresolved(leaf, program)) {
      target.resolveDescriptor(leaf, program, descriptor);
    }
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
