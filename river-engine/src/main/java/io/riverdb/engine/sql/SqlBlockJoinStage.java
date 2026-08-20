package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Materializes the deepest two-table source into one canonical block boundary. */
final class SqlBlockJoinStage {
  private final BoundSqlStatement bound;
  private final SqlBlockSource source;
  private final SqlBoundPredicateEvaluator predicates;
  private final SqlRowProjectionEvaluator projections;

  SqlBlockJoinStage(
      BoundSqlStatement statement,
      SqlBlockSource blockSource,
      SqlBoundPredicateEvaluator predicateEvaluator,
      SqlRowProjectionEvaluator projectionEvaluator) {
    bound = statement;
    source = blockSource;
    predicates = predicateEvaluator;
    projections = projectionEvaluator;
  }

  StatusCode prepare() {
    StatusCode status = predicates.prepare();
    return status.isOk() ? projections.prepare(bound) : status;
  }

  StatusCode materialize(
      int block, SqlBlockRowStore output, SqlBlockRow sourceRow) {
    StatusCode status = output.begin(
        bound.blockPlans().schema(block), -1, false);
    if (status.isOk()) status = source.beginJoin();
    while (status.isOk()) {
      status = source.nextJoin(sourceRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = output.append(sourceRow);
    }
    status = source.finishJoin(status);
    sourceRow.reset(0);
    return status.isOk() ? output.finish() : status;
  }
}
