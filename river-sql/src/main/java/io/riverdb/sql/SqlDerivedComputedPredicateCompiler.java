package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Composes the one admitted outer derived predicate program. */
final class SqlDerivedComputedPredicateCompiler {
  private final SqlDerivedProjectionCompiler projections;
  private final SqlDerivedPredicateValues values;

  SqlDerivedComputedPredicateCompiler(
      SqlDerivedProjectionCompiler projectionCompiler,
      SqlDerivedPredicateValues predicateValues) {
    projections = projectionCompiler;
    values = predicateValues;
  }

  StatusCode copy(
      int block, SqlCommand source, int predicate, SqlCommand destination) {
    if (block != 0 || source.isColumnPredicate(predicate)) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    StatusCode status = projections.copyPredicate(
        block, source.predicateExpression(predicate), destination);
    return finish(source, predicate, destination, status);
  }

  StatusCode promote(
      SqlCommand source, int predicate, SqlCommand destination) {
    if (source.isColumnPredicate(predicate)) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    StatusCode status = projections.copyPredicateReference(
        0, source.predicateColumnName(predicate), destination);
    return finish(source, predicate, destination, status);
  }

  private StatusCode finish(
      SqlCommand source,
      int predicate,
      SqlCommand destination,
      StatusCode status) {
    if (status.isOk()) status = values.copy(source, predicate, destination);
    if (status.isOk()) {
      destination.publishPredicateExpression(destination.predicateCount() - 1);
    }
    return status;
  }
}
