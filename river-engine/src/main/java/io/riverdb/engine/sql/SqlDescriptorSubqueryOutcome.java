package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlQuery;

/** SQL three-valued accumulation for one predicate-subquery scan. */
final class SqlDescriptorSubqueryOutcome {
  private final ExactDecimal128.Scratch decimal128 = new ExactDecimal128.Scratch();
  private int kind;
  private boolean negated;
  private SqlComparison comparison;
  private int leftDescriptor;
  private int childDescriptor;
  private boolean leftNull;
  private long leftHigh;
  private long left;
  private long rows;
  private boolean matched;
  private boolean containsNull;
  private int truth;

  void configure(
      int edgeKind, boolean edgeNegated, SqlComparison edgeComparison,
      int inputDescriptor, int resultDescriptor) {
    kind = edgeKind;
    negated = edgeNegated;
    comparison = edgeComparison;
    leftDescriptor = inputDescriptor;
    childDescriptor = resultDescriptor;
  }

  void begin(boolean inputNull, long inputHigh, long input) {
    leftNull = inputNull;
    leftHigh = inputHigh;
    left = input;
    rows = 0;
    matched = false;
    containsNull = false;
    truth = -1;
  }

  void cached(int value) { truth = value; }

  void accept(boolean childNull, long childHigh, long child) {
    rows++;
    if (kind == SqlQuery.SUBQUERY_EXISTS) matched = true;
    else {
      containsNull |= childNull;
      SqlComparison effective = comparison == null ? SqlComparison.EQUAL : comparison;
      if (!childNull && !leftNull && matches(
          leftHigh, left, childHigh, child, effective)) matched = true;
    }
  }

  boolean complete() {
    return kind == SqlQuery.SUBQUERY_EXISTS && matched
        || kind == SqlQuery.SUBQUERY_SCALAR && rows > 1
        || kind == SqlQuery.SUBQUERY_MEMBERSHIP && matched;
  }

  StatusCode finish() {
    if (kind == SqlQuery.SUBQUERY_SCALAR && rows > 1) {
      return StatusCode.CARDINALITY_VIOLATION;
    }
    if (kind == SqlQuery.SUBQUERY_EXISTS) truth = matched ^ negated ? 1 : 0;
    else if (kind == SqlQuery.SUBQUERY_SCALAR) {
      truth = rows == 0 || containsNull || leftNull ? -1 : matched ? 1 : 0;
    } else if (matched) truth = negated ? 0 : 1;
    else if (rows == 0) truth = negated ? 1 : 0;
    else truth = containsNull || leftNull ? -1 : negated ? 1 : 0;
    return StatusCode.OK;
  }

  int truth() { return truth; }
  long rows() { return rows; }

  private boolean matches(
      long inputHigh, long input, long resultHigh, long result,
      SqlComparison effective) {
    int compared = SqlNumericComparison.compare(
        inputHigh, input, leftDescriptor,
        resultHigh, result, childDescriptor,
        decimal128);
    return switch (effective) {
      case EQUAL -> compared == 0;
      case NOT_EQUAL -> compared != 0;
      case LESS_THAN -> compared < 0;
      case LESS_OR_EQUAL -> compared <= 0;
      case GREATER_THAN -> compared > 0;
      case GREATER_OR_EQUAL -> compared >= 0;
      default -> false;
    };
  }

}
