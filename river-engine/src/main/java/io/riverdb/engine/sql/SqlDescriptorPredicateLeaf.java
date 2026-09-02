package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
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

  int evaluate(int leaf, SqlDescriptorValueSource values) {
    int test = program.leafTest(leaf);
    if (test >= SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS
        && test <= SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP) {
      int column = bindings.column(leaf);
      boolean isNull = column >= 0 && values.isNull(column);
      long high = isNull || column < 0 ? 0 : values.highValue(column);
      long value = isNull || column < 0 ? 0 : values.value(column);
      status = subqueries.evaluate(
          program.subqueryEdge(leaf), isNull, high, value, values);
      return status.isOk() ? subqueries.truth() : -1;
    }
    int column = bindings.column(leaf);
    if (test != SqlBooleanPredicateProgram.TEST_COMPARISON) {
      int result = special.evaluate(test, leaf, column, values);
      status = special.status();
      return result;
    }
    if (values.isNull(column)) return -1;
    int compared = literals.compare(
        leaf,
        column,
        values,
        bindings.literalHigh(leaf),
        bindings.literal(leaf),
        bindings.descriptor(leaf));
    status = literals.status();
    if (!status.isOk()) return -1;
    return switch (bindings.comparison(leaf)) {
      case EQUAL -> compared == 0 ? 1 : 0;
      case NOT_EQUAL -> compared != 0 ? 1 : 0;
      case LESS_THAN -> compared < 0 ? 1 : 0;
      case LESS_OR_EQUAL -> compared <= 0 ? 1 : 0;
      case GREATER_THAN -> compared > 0 ? 1 : 0;
      case GREATER_OR_EQUAL -> compared >= 0 ? 1 : 0;
      default -> 0;
    };
  }

}
