package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlQuery;

/** Scans one reached EXISTS, scalar, or membership child to completion. */
final class SqlSubqueryValueScanner {
  private final BoundSqlQuery query;
  private final SqlSubqueryFrames frames;
  private final SqlNestedProjectionExecution projections;
  private final SqlSubqueryResultCache cache;
  private final SqlExpressionEvaluator expressions;
  private final SqlSubqueryCandidateEvaluator candidates;
  private final SqlSubqueryPlan plan;
  private final int[] counts = new int[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final boolean[] matched = new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final boolean[] nullResults = new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];

  SqlSubqueryValueScanner(
      BoundSqlQuery boundQuery,
      SqlSubqueryFrames frameBank,
      SqlNestedProjectionExecution projectionExecution,
      SqlSubqueryResultCache resultCache,
      SqlExpressionEvaluator evaluator,
      SqlSubqueryCandidateEvaluator candidateEvaluator,
      SqlSubqueryPlan subqueryPlan) {
    query = boundQuery;
    frames = frameBank;
    projections = projectionExecution;
    cache = resultCache;
    expressions = evaluator;
    candidates = candidateEvaluator;
    plan = subqueryPlan;
  }

  StatusCode evaluate(
      int edge, SqlPredicateOperand left, SqlSubqueryLeafEvaluator.Truth truth) {
    plan.execute(edge);
    if (cache.enabled(edge) && cache.available(edge)) {
      truth.set(cache.truth(edge, left));
      return StatusCode.OK;
    }
    StatusCode status = frames.own(query.edgeParent(edge));
    if (!status.isOk()) return status;
    int child = query.edgeChild(edge);
    return query.edgeKind(edge) == SqlQuery.SUBQUERY_EXISTS
        ? existence(edge, child, truth) : values(edge, child, left, truth);
  }

  private StatusCode existence(
      int edge, int child, SqlSubqueryLeafEvaluator.Truth truth) {
    if (query.block(child).rowLimit() == 0) {
      publishExistence(edge, false, truth);
      return StatusCode.OK;
    }
    StatusCode status = frames.begin(child);
    if (!status.isOk()) return frames.finish(child, status);
    boolean exists = false;
    while (status.isOk()) {
      status = frames.next(child);
      if (status == StatusCode.CONFLICT) break;
      if (!status.isOk()) return frames.finish(child, status);
      plan.visit(edge);
      status = candidates.accept(child);
      if (!status.isOk()) return frames.finish(child, status);
      if (candidates.accepted(child)) {
        plan.accept(edge);
        exists = true;
        break;
      }
      frames.release(child);
    }
    status = frames.finish(child, StatusCode.OK);
    if (status.isOk()) publishExistence(edge, exists, truth);
    return status;
  }

  private void publishExistence(
      int edge, boolean exists, SqlSubqueryLeafEvaluator.Truth truth) {
    if (query.edgeNegated(edge)) exists = !exists;
    int value = exists
        ? SqlBooleanPredicateEvaluator.TRUE : SqlBooleanPredicateEvaluator.FALSE;
    truth.set(value);
    if (cache.enabled(edge)) cache.completeTruth(edge, value);
  }

  private StatusCode values(
      int edge,
      int child,
      SqlPredicateOperand left,
      SqlSubqueryLeafEvaluator.Truth truth) {
    if (query.block(child).rowLimit() == 0) {
      publishEmpty(edge, truth);
      return StatusCode.OK;
    }
    StatusCode status = frames.begin(child);
    if (!status.isOk()) return frames.finish(child, status);
    boolean cached = cache.enabled(edge);
    if (cached) cache.start(edge);
    counts[child] = 0;
    matched[child] = false;
    nullResults[child] = false;
    while (status.isOk()) {
      status = frames.next(child);
      if (status == StatusCode.CONFLICT) break;
      if (!status.isOk()) return abort(child, status);
      plan.visit(edge);
      status = candidates.accept(child);
      if (!status.isOk()) return abort(child, status);
      if (!candidates.accepted(child)) {
        frames.release(child);
        continue;
      }
      plan.accept(edge);
      status = acceptValue(edge, child, left, cached);
      if (!status.isOk()) return abort(child, status);
      if (counts[child] >= query.block(child).rowLimit()) break;
    }
    status = frames.finish(child, StatusCode.OK);
    if (!status.isOk()) {
      return status;
    }
    if (cached) cache.completeValues(edge, counts[child]);
    publish(edge, child, left, truth);
    return StatusCode.OK;
  }

  private StatusCode acceptValue(
      int edge,
      int child,
      SqlPredicateOperand left,
      boolean cached) {
    if (++counts[child] > SqlSubqueryResultCache.MAXIMUM_RESULTS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    SqlPredicateOperand candidate = frames.projected(child);
    StatusCode status = projections.evaluate(
        child, frames.key(child), frames.row(child), frames.table(child), candidate);
    if (!status.isOk()) return status;
    if (candidate.nullValue()) nullResults[child] = true;
    else if (left != null && !left.nullValue()
        && compare(left, candidate, comparison(edge))) matched[child] = true;
    if (cached) status = cache.append(edge, candidate);
    frames.release(child);
    if (!status.isOk()) return status;
    return query.edgeKind(edge) == SqlQuery.SUBQUERY_SCALAR && counts[child] > 1
        ? StatusCode.CARDINALITY_VIOLATION : StatusCode.OK;
  }

  private void publish(
      int edge,
      int child,
      SqlPredicateOperand left,
      SqlSubqueryLeafEvaluator.Truth truth) {
    int value = query.edgeKind(edge) == SqlQuery.SUBQUERY_SCALAR
        ? scalar(left, frames.projected(child), counts[child],
            query.edgeComparison(edge))
        : membership(left, counts[child], matched[child], nullResults[child]);
    truth.set(query.edgeNegated(edge) ? negate(value) : value);
  }

  private void publishEmpty(int edge, SqlSubqueryLeafEvaluator.Truth truth) {
    int value = query.edgeKind(edge) == SqlQuery.SUBQUERY_SCALAR
        ? SqlBooleanPredicateEvaluator.UNKNOWN
        : query.edgeNegated(edge)
            ? SqlBooleanPredicateEvaluator.TRUE : SqlBooleanPredicateEvaluator.FALSE;
    truth.set(value);
    if (cache.enabled(edge)) {
      cache.start(edge);
      cache.completeValues(edge, 0);
    }
  }

  private StatusCode abort(int child, StatusCode status) {
    return frames.finish(child, status);
  }

  private int scalar(
      SqlPredicateOperand left,
      SqlPredicateOperand right,
      int count,
      SqlComparison comparison) {
    if (count == 0 || left == null || left.nullValue() || right.nullValue()) {
      return SqlBooleanPredicateEvaluator.UNKNOWN;
    }
    return compare(left, right, comparison)
        ? SqlBooleanPredicateEvaluator.TRUE : SqlBooleanPredicateEvaluator.FALSE;
  }

  private static int membership(
      SqlPredicateOperand left, int count, boolean matched, boolean nullResult) {
    if (count == 0) return SqlBooleanPredicateEvaluator.FALSE;
    if (left == null || left.nullValue()) return SqlBooleanPredicateEvaluator.UNKNOWN;
    if (matched) return SqlBooleanPredicateEvaluator.TRUE;
    return nullResult
        ? SqlBooleanPredicateEvaluator.UNKNOWN : SqlBooleanPredicateEvaluator.FALSE;
  }

  private boolean compare(
      SqlPredicateOperand left, SqlPredicateOperand right, SqlComparison comparison) {
    int compared = SqlTypeDescriptor.typeId(left.descriptor())
            == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? SqlBooleanTextComparator.compare(left, right)
        : expressions.compareExact(
            left.value(), left.descriptor(), right.value(), right.descriptor());
    return switch (comparison) {
      case EQUAL -> compared == 0;
      case NOT_EQUAL -> compared != 0;
      case LESS_THAN -> compared < 0;
      case LESS_OR_EQUAL -> compared <= 0;
      case GREATER_THAN -> compared > 0;
      case GREATER_OR_EQUAL -> compared >= 0;
      case HALF_OPEN_RANGE, IN, NOT_IN -> false;
    };
  }

  private SqlComparison comparison(int edge) {
    return query.edgeKind(edge) == SqlQuery.SUBQUERY_MEMBERSHIP
        ? SqlComparison.EQUAL : query.edgeComparison(edge);
  }

  private static int negate(int value) {
    return value == SqlBooleanPredicateEvaluator.UNKNOWN ? value
        : value == SqlBooleanPredicateEvaluator.TRUE
            ? SqlBooleanPredicateEvaluator.FALSE : SqlBooleanPredicateEvaluator.TRUE;
  }
}
