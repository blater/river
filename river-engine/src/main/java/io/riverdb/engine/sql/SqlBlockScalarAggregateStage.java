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
  private final SqlBlockOutputOrder outputOrder;
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
      SqlBlockAggregatePublisher aggregatePublisher,
      SqlBlockOutputOrder blockOutputOrder) {
    bound = statement;
    source = blockSource;
    projector = stageProjector;
    having = havingEvaluator;
    accumulator = aggregateAccumulator;
    publisher = aggregatePublisher;
    outputOrder = blockOutputOrder;
  }

  StatusCode execute(
      int block, SqlBlockRowStore input, SqlBlockRowStore output) {
    StatusCode status = accumulator.reset(bound.aggregates);
    if (!status.isOk()) return status;
    status = source.begin(input, sourceRow);
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
    status = outputOrder.beginOutput(
        bound.command, bound.blockPlans().schema(block), output);
    if (status.isOk() && having.matched()) {
      status = publisher.publish(block, accumulator, null, outputRow, false);
      if (status.isOk()) status = output.append(outputRow);
    }
    return status.isOk() ? output.finish() : status;
  }

  StatusCode executeJoined(
      int block, SqlBlockRowStore input, SqlBlockRowStore output) {
    StatusCode status = accumulator.reset(bound.aggregates);
    input.rewind();
    while (status.isOk()) {
      status = input.next(sourceRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = accumulator.accumulateBlock(bound.aggregates, sourceRow);
    }
    StatusCode closed = input.close();
    if (status.isOk()) status = closed;
    if (status.isOk()) status = accumulator.finish(bound.aggregates);
    if (status.isOk()) status = having.evaluate(
        bound.command, accumulator, 0, true, null, 0);
    if (!status.isOk()) return status;
    status = outputOrder.beginOutput(
        bound.command, bound.blockPlans().schema(block), output);
    if (status.isOk() && having.matched()) {
      status = publisher.publish(block, accumulator, null, outputRow, false);
      if (status.isOk()) status = output.append(outputRow);
    }
    return status.isOk() ? output.finish() : status;
  }

  StatusCode publishAccumulated(
      int block, SqlBlockRowStore output) {
    StatusCode status = accumulator.finish(bound.aggregates);
    if (status.isOk()) status = having.evaluate(
        bound.command, accumulator, 0, true, null, 0);
    if (!status.isOk()) return status;
    status = outputOrder.beginOutput(
        bound.command, bound.blockPlans().schema(block), output);
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
