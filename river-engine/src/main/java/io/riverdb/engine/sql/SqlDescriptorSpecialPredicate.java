package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Evaluates direct-column NULL, truth, membership, and range predicate leaves. */
final class SqlDescriptorSpecialPredicate {
  private final SqlDescriptorLiteralComparison literals;
  private SqlBooleanPredicateProgram program;
  private SqlDescriptorPredicateBindings bindings;
  private StatusCode status = StatusCode.OK;

  SqlDescriptorSpecialPredicate(SqlDescriptorLiteralComparison comparison) {
    literals = comparison;
  }

  void prepare(
      SqlBooleanPredicateProgram source, SqlDescriptorPredicateBindings bound) {
    program = source;
    bindings = bound;
  }

  int evaluate(
      int test, int leaf, int column, SqlDescriptorValueSource values) {
    status = StatusCode.OK;
    if (test == SqlBooleanPredicateProgram.TEST_NULL) {
      return truth(values.isNull(column), program.leafNegated(leaf));
    }
    if (test == SqlBooleanPredicateProgram.TEST_TRUTH) {
      return truth(truthValue(leaf, column, values), program.leafNegated(leaf));
    }
    if (test == SqlBooleanPredicateProgram.TEST_BOOLEAN) {
      return values.isNull(column) ? -1 : values.value(column) != 0 ? 1 : 0;
    }
    if (values.isNull(column)) return -1;
    return test == SqlBooleanPredicateProgram.TEST_MEMBERSHIP
        ? membership(leaf, column, values)
        : test == SqlBooleanPredicateProgram.TEST_BETWEEN
            ? between(leaf, column, values) : unsupported();
  }

  StatusCode status() { return status; }

  private boolean truthValue(
      int leaf, int column, SqlDescriptorValueSource values) {
    SqlComparison comparison = bindings.comparison(leaf);
    return comparison == null
        ? values.isNull(column)
        : !values.isNull(column)
            && (comparison == SqlComparison.EQUAL
                ? values.value(column) != 0 : values.value(column) == 0);
  }

  private int membership(
      int leaf, int column, SqlDescriptorValueSource values) {
    boolean unknown = false;
    for (int member = 0; member < program.leafMemberCount(leaf); member++) {
      if (program.memberNull(leaf, member)) {
        unknown = true;
        continue;
      }
      int compared = literals.compare(
          leaf, column, values,
          program.memberHigh(leaf, member),
          program.memberValue(leaf, member), program.memberDescriptor(leaf, member));
      status = literals.status();
      if (!status.isOk()) return -1;
      if (compared == 0) return truth(true, program.leafNegated(leaf));
    }
    return unknown ? -1 : truth(false, program.leafNegated(leaf));
  }

  private int between(
      int leaf, int column, SqlDescriptorValueSource values) {
    int lowerDescriptor = program.programDescriptor(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER, 0);
    int upperDescriptor = program.programDescriptor(
        leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER, 0);
    if (lowerDescriptor == 0 || upperDescriptor == 0) return -1;
    int lower = compareProgram(
        leaf, column, values, SqlBooleanPredicateProgram.PROGRAM_LOWER, lowerDescriptor);
    if (!status.isOk()) return -1;
    int upper = compareProgram(
        leaf, column, values, SqlBooleanPredicateProgram.PROGRAM_UPPER, upperDescriptor);
    return status.isOk()
        ? truth(lower >= 0 && upper <= 0, program.leafNegated(leaf)) : -1;
  }

  private int compareProgram(
      int leaf,
      int column,
      SqlDescriptorValueSource values,
      int side,
      int descriptor) {
    int compared = literals.compare(
        leaf,
        column,
        values,
        program.programOperandHigh(leaf, side, 0),
        program.programOperand(leaf, side, 0),
        descriptor);
    status = literals.status();
    return compared;
  }

  private int unsupported() {
    status = StatusCode.FEATURE_NOT_SUPPORTED;
    return -1;
  }

  private static int truth(boolean matched, boolean negated) {
    return matched != negated ? 1 : 0;
  }
}
