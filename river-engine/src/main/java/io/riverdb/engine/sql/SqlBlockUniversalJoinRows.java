package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;

/** Retained adapter for descriptor and multi-role block JOIN execution. */
final class SqlBlockUniversalJoinRows implements SqlBlockJoinRows {
  private final RelationalSession session;
  private final SqlExpressionEvaluator expressions;
  private final SqlTemporalContext temporal;
  private final SqlSubqueryGraphExecution subqueries;
  private final SqlRowProjectionEvaluator projections;
  private final SqlBlockSource metrics;
  private final SqlSessionShapeBudget budget;
  private SqlSubqueryUniversalJoinFrame frame;
  private int frameBlock = -1;
  private boolean frameNested;

  SqlBlockUniversalJoinRows(
      RelationalSession relationalSession,
      SqlExpressionEvaluator expressionEvaluator,
      SqlTemporalContext temporalContext,
      SqlSubqueryGraphExecution graph,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlBlockSource metricSource,
      SqlSessionShapeBudget shapeBudget) {
    session = relationalSession;
    expressions = expressionEvaluator;
    temporal = temporalContext;
    subqueries = graph;
    projections = projectionEvaluator;
    metrics = metricSource;
    budget = shapeBudget;
  }

  StatusCode prepare(
      int block,
      boolean nested,
      SqlCommand command,
      SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where,
      int orderedInnerColumn) {
    if (frame == null || frameBlock != block || frameNested != nested) {
      frame = new SqlSubqueryUniversalJoinFrame(
          session, expressions, temporal, block,
          nested ? subqueries : null,
          nested ? subqueries.rows() : null,
          nested ? subqueries.plan() : null,
          budget);
      frameBlock = block;
      frameNested = nested;
    }
    StatusCode status = frame.prepare(command, context, where, orderedInnerColumn);
    if (status.isOk() && nested) subqueries.registerExternalUniversal(block, frame.rows());
    if (!status.isOk()) {
      StatusCode reset = frame.reset();
      if (!reset.isOk()) status = reset;
    }
    return status;
  }

  @Override
  public StatusCode begin() { return frame.begin(); }

  @Override
  public StatusCode next(SqlBlockRow row) {
    StatusCode status = frame.next();
    return status.isOk() ? projections.projectUniversalJoin(frame.rows(), row) : status;
  }

  @Override
  public StatusCode finish(StatusCode body) {
    if (body.isOk()) frame.publishMetrics(metrics);
    StatusCode finish = frame.finish();
    StatusCode reset = frame.reset();
    subqueries.clearExternalUniversal();
    StatusCode cleanup = finish.isOk() ? reset : finish;
    return cleanup.isOk() ? body : cleanup;
  }

  @Override
  public StatusCode skip() {
    return finish(StatusCode.OK);
  }

  @Override
  public StatusCode close() {
    subqueries.clearExternalUniversal();
    return frame == null ? StatusCode.OK : frame.reset();
  }

  @Override
  public boolean hasResources() { return frame != null && frame.hasResources(); }
}
