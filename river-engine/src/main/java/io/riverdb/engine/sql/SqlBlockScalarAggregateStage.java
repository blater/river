package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Executes one scalar aggregate block from a single child pass. */
final class SqlBlockScalarAggregateStage {
  private final BoundSqlStatement bound;
  private final SqlBlockSource source;
  private final SqlBlockStageProjector projector;
  private final SqlHavingEvaluator having;
  private final SqlAggregateAccumulatorSet accumulator;
  private final SqlBlockAggregatePublisher publisher;
  private final SqlBlockStageProjector.Projected projected =
      new SqlBlockStageProjector.Projected();
  private final SqlBlockRow sourceRow = new SqlBlockRow();
  private final SqlBlockRow operandRow = new SqlBlockRow();
  private final SqlBlockRow outputRow = new SqlBlockRow();

  SqlBlockScalarAggregateStage(
      BoundSqlStatement statement,
      SqlBlockSource blockSource,
      SqlBlockStageProjector stageProjector,
      SqlHavingEvaluator havingEvaluator,
      SqlAggregateAccumulatorSet aggregateAccumulator,
      SqlBlockAggregatePublisher aggregatePublisher) {
    bound = statement;
    source = blockSource;
    projector = stageProjector;
    having = havingEvaluator;
    accumulator = aggregateAccumulator;
    publisher = aggregatePublisher;
  }

  StatusCode execute(
      int block, SqlBlockRowStore input, SqlBlockRowStore output, int sortKey) {
    accumulator.reset(bound.aggregates);
    StatusCode status = source.begin(input);
    while (status.isOk()) {
      status = source.next(input, sourceRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = projector.project(block, sourceRow, operandRow, projected);
      if (status.isOk() && projected.available) {
        status = accumulator.accumulateBlock(bound.aggregates, operandRow);
      }
    }
    status = source.finish(input, status);
    if (status.isOk()) status = accumulator.finish(bound.aggregates);
    if (status.isOk()) status = having.evaluate(
        bound.command, accumulator, 0, true, null, 0);
    if (!status.isOk()) return status;
    status = output.begin(
        bound.blockPlans().schema(block), sortKey,
        bound.command.isDescendingOrder());
    if (status.isOk() && having.matched()) {
      status = publisher.publish(block, accumulator, null, outputRow, false);
      if (status.isOk()) status = output.append(outputRow);
    }
    return status.isOk() ? output.finish() : status;
  }

  void reset() {
    sourceRow.reset(0);
    operandRow.reset(0);
    outputRow.reset(0);
  }
}
