package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;

/** Evaluates one block-local predicate set over an owned virtual row. */
final class SqlBlockPredicateEvaluator {
  private final SqlExpressionEvaluator exact;
  private final SqlRowProjectionEvaluator computed;
  private final SqlBlockTextPredicateEvaluator text;

  SqlBlockPredicateEvaluator(
      SqlExpressionEvaluator exactEvaluator,
      SqlRowProjectionEvaluator computedEvaluator) {
    exact = exactEvaluator;
    computed = computedEvaluator;
    text = new SqlBlockTextPredicateEvaluator(computedEvaluator);
  }

  StatusCode matches(
      SqlCommand command,
      SqlBlockSchema schema,
      SqlBlockRow row,
      BoundSqlStatement bound,
      Match result) {
    boolean conjunction = true;
    boolean disjunction = false;
    for (int predicate = 0; predicate < command.predicateCount(); predicate++) {
      if (command.predicateStartsDisjunction(predicate)) {
        disjunction |= conjunction;
        conjunction = true;
      }
      if (!conjunction) continue;
      StatusCode status = predicate(command, schema, row, bound, predicate, result);
      if (!status.isOk()) return status;
      conjunction = result.matched;
    }
    result.matched = disjunction || conjunction;
    return StatusCode.OK;
  }

  private StatusCode predicate(
      SqlCommand command,
      SqlBlockSchema schema,
      SqlBlockRow row,
      BoundSqlStatement bound,
      int predicate,
      Match result) {
    if (command.predicateExpression(predicate) != null) {
      StatusCode status = computed.evaluatePredicateBlock(row);
      if (!status.isOk()) return status;
      return compareComputed(command, predicate, result);
    }
    int column = bound.predicateColumns[predicate];
    if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    boolean nullValue = row.nullValue(column);
    if (command.isNullPredicate(predicate)) {
      result.matched = nullValue != command.isNullPredicateNegated(predicate);
      return StatusCode.OK;
    }
    if (nullValue) {
      result.matched = false;
      return StatusCode.OK;
    }
    if (schema.varchar(column)) {
      return text.compare(command, row, column,
          bound.blockPredicateRightColumns[predicate], predicate, result);
    }
    long value = row.value(column);
    if (command.isColumnPredicate(predicate)) {
      int right = bound.blockPredicateRightColumns[predicate];
      result.matched = right >= 0 && !row.nullValue(right)
          && exact.matchesComparison(
              value, schema.descriptor(column), command.comparison(predicate),
              row.value(right), schema.descriptor(right));
    } else {
      result.matched = exact.matchesComparison(
          value, schema.descriptor(column), command, predicate);
    }
    return StatusCode.OK;
  }

  private StatusCode compareComputed(
      SqlCommand command, int predicate, Match result) {
    if (command.isNullPredicate(predicate)) {
      result.matched = computed.predicateNull()
          != command.isNullPredicateNegated(predicate);
      return StatusCode.OK;
    }
    if (computed.predicateNull()) {
      result.matched = false;
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.typeId(computed.predicateDescriptor())
        == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return text.compareGenerated(command, predicate, result);
    }
    result.matched = exact.matchesComparison(
        computed.predicateValue(), computed.predicateDescriptor(), command, predicate);
    return StatusCode.OK;
  }

  void reset() {
    text.reset();
  }

  static final class Match { boolean matched; }
}
