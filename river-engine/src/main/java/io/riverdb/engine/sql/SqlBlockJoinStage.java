package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Materializes the deepest two-table source into one canonical block boundary. */
final class SqlBlockJoinStage {
  private final BoundSqlStatement bound;
  private final SqlBlockSource source;
  private final SqlRowProjectionEvaluator projections;
  private final SqlSubqueryGraphExecution subqueries;

  SqlBlockJoinStage(
      BoundSqlStatement statement,
      SqlBlockSource blockSource,
      SqlSubqueryGraphExecution graph,
      SqlRowProjectionEvaluator projectionEvaluator) {
    bound = statement;
    source = blockSource;
    subqueries = graph;
    projections = projectionEvaluator;
  }

  StatusCode prepare(int block) {
    boolean nested = bound.executableQuery.edgeCount() > 0;
    StatusCode status = source.configureJoin(
        bound.existingJoinContext(block),
        bound.blockPlans().command(block),
        nested ? bound.nestedBoolean(block) : bound.whereBoolean,
        nested ? subqueries.joinPredicates(block) : null);
    return status.isOk() ? projections.prepare(bound) : status;
  }

  StatusCode materialize(
      int block, SqlBlockRowStore output, SqlBlockRow sourceRow) {
    long limit = bound.blockPlans().command(block).rowLimit();
    StatusCode status = output.begin(
        bound.blockPlans().schema(block), -1, false);
    boolean began = false;
    if (status.isOk() && limit > 0) {
      status = source.beginJoin();
      began = status.isOk();
    } else if (status.isOk()) {
      source.resetJoinMetrics();
    }
    long accepted = 0;
    while (status.isOk() && accepted < limit) {
      status = source.nextJoin(sourceRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = output.append(sourceRow);
      if (status.isOk()) accepted++;
    }
    if (began && !subqueries.hasResources()) status = source.finishJoin(status);
    sourceRow.reset(0);
    return status.isOk() ? output.finish() : status;
  }
}
