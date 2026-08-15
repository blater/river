package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Evaluates the flat HAVING predicate set with AND precedence over OR. */
final class SqlHavingEvaluator {
  private static final int UNKNOWN = SqlHavingPredicateComparator.UNKNOWN;
  private final SqlRowProjectionEvaluator programs;
  private final SqlHavingPredicateComparator predicate;
  private boolean matched;

  SqlHavingEvaluator(
      SqlExpressionEvaluator expressions,
      SqlRowProjectionEvaluator programEvaluator) {
    programs = programEvaluator;
    predicate = new SqlHavingPredicateComparator(expressions, programEvaluator);
  }

  StatusCode evaluate(
      SqlCommand command,
      SqlBoundHavingPrograms having,
      SqlAggregateAccumulatorSet aggregates,
      long groupValue,
      boolean groupNull,
      byte[] groupText,
      int groupTextLength) {
    int disjunction = 0;
    int conjunction = 1;
    for (int index = 0; index < command.havingPredicateCount(); index++) {
      StatusCode status = programs.evaluateHaving(
          index, aggregates.values(), aggregates.nulls(), groupValue, groupNull);
      if (!status.isOk()) return reset(status);
      predicate.evaluate(
          command, having, aggregates, groupText, groupTextLength, index);
      if (!predicate.status().isOk()) return reset(predicate.status());
      conjunction = and(conjunction, predicate.truth());
      if (command.havingDisjunction(index)) {
        disjunction = or(disjunction, conjunction);
        conjunction = 1;
      }
    }
    matched = command.havingPredicateCount() == 0
        || or(disjunction, conjunction) == 1;
    return reset(StatusCode.OK);
  }

  boolean matched() { return matched; }

  private StatusCode reset(StatusCode status) {
    predicate.reset();
    return status;
  }

  private static int and(int left, int right) {
    return left == 0 || right == 0 ? 0
        : left == UNKNOWN || right == UNKNOWN ? UNKNOWN : 1;
  }

  private static int or(int left, int right) {
    return left == 1 || right == 1 ? 1
        : left == UNKNOWN || right == UNKNOWN ? UNKNOWN : 0;
  }
}
