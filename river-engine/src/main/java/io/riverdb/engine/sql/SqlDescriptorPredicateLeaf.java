package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.SqlValueAccess;
import io.riverdb.sql.SqlBooleanPredicateProgram;

/** Evaluates one bound predicate leaf without allocating per row. */
final class SqlDescriptorPredicateLeaf {
  private final SqlDescriptorLiteralComparison literals =
      new SqlDescriptorLiteralComparison();
  private final SqlDescriptorSpecialPredicate special =
      new SqlDescriptorSpecialPredicate(literals);
  private SqlBooleanPredicateProgram program;
  private SqlDescriptorPredicateBindings bindings;
  private SqlDescriptorSubqueryExecution subqueries;
  private StatusCode status = StatusCode.OK;

  void prepare(
      SqlBooleanPredicateProgram source, SqlDescriptorPredicateBindings bound,
      SqlDescriptorSubqueryExecution nested) {
    program = source;
    bindings = bound;
    subqueries = nested;
    literals.prepare(source, bound);
    special.prepare(source, bound);
  }

  StatusCode status() { return status; }

  void begin() { status = StatusCode.OK; }

  int evaluate(int leaf, SqlValueAccess values) {
    int test = program.leafTest(leaf);
    if (test >= SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS
        && test <= SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP) {
      return evaluateSubquery(leaf, values);
    }
    int column = bindings.column(leaf);
    if (test != SqlBooleanPredicateProgram.TEST_COMPARISON) {
      return evaluateSpecial(test, leaf, column, values);
    }
    if (values.isNull(column)) return -1;
    return evaluateComparison(leaf, column, values);
  }

  private int evaluateSubquery(int leaf, SqlValueAccess values) {
    int column = bindings.column(leaf);
    boolean isNull = column >= 0 && values.isNull(column);
    long high = isNull || column < 0 ? 0 : values.highValueAt(column);
    long value = isNull || column < 0 ? 0 : values.valueAt(column);
    status = subqueries.evaluate(
        program.subqueryEdge(leaf), isNull, high, value, values);
    return status.isOk() ? subqueries.truth() : -1;
  }

  private int evaluateSpecial(
      int test, int leaf, int column, SqlValueAccess values) {
    int result = special.evaluate(test, leaf, column, values);
    status = special.status();
    return result;
  }

  private int evaluateComparison(
      int leaf, int column, SqlValueAccess values) {
    int compared = literals.compare(
        leaf,
        column,
        values,
        bindings.literalHigh(leaf),
        bindings.literal(leaf),
        bindings.descriptor(leaf));
    status = literals.status();
    if (!status.isOk()) return -1;
    return SqlDescriptorComparison.matches(compared, bindings.comparison(leaf)) ? 1 : 0;
  }
}
