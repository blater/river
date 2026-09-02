package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Stateful reduction of one group-sorted operand store. */
final class SqlBlockGroupedAggregateExecution {
  private final BoundSqlStatement bound;
  private final SqlHavingEvaluator having;
  private final SqlAggregateAccumulatorSet accumulator;
  private final SqlBlockAggregatePublisher publisher;
  private final SqlBlockOutputOrder outputOrder;
  private final SqlBlockRow sourceRow = new SqlBlockRow();
  private final SqlBlockRow lookaheadRow = new SqlBlockRow();
  private final SqlBlockRow groupKeyRow = new SqlBlockRow();
  private final SqlBlockRow outputRow = new SqlBlockRow();

  SqlBlockGroupedAggregateExecution(
      BoundSqlStatement statement,
      SqlHavingEvaluator havingEvaluator,
      SqlAggregateAccumulatorSet aggregateAccumulator,
      SqlBlockAggregatePublisher aggregatePublisher,
      SqlBlockOutputOrder blockOutputOrder) {
    bound = statement;
    having = havingEvaluator;
    accumulator = aggregateAccumulator;
    publisher = aggregatePublisher;
    outputOrder = blockOutputOrder;
  }

  StatusCode execute(
      int block, SqlBlockRowStore sorted, SqlBlockRowStore output) {
    StatusCode status = outputOrder.beginOutput(
        bound.command, bound.blockPlans().schema(block), output);
    boolean lookahead = false;
    while (status.isOk()) {
      status = lookahead ? sourceRow.copyFrom(lookaheadRow) : sorted.next(sourceRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      lookahead = false;
      status = groupKeyRow.copyFrom(sourceRow);
      if (!status.isOk()) break;
      status = accumulator.reset(bound.aggregates);
      if (!status.isOk()) break;
      do {
        status = accumulator.accumulateBlock(bound.aggregates, sourceRow);
        if (!status.isOk()) break;
        status = sorted.next(lookaheadRow);
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.OK;
          break;
        }
        if (!status.isOk()) break;
        lookahead = !SqlBlockRowEquality.same(groupKeyRow, lookaheadRow, 0, groupCount());
        if (!lookahead) status = sourceRow.copyFrom(lookaheadRow);
      } while (!lookahead);
      if (status.isOk()) status = publish(block, output);
    }
    StatusCode closed = sorted.close();
    if (status.isOk()) status = closed;
    return status.isOk() ? output.finish() : status;
  }

  void reset() {
    sourceRow.reset(0);
    lookaheadRow.reset(0);
    groupKeyRow.reset(0);
    outputRow.reset(0);
  }

  private StatusCode publish(int block, SqlBlockRowStore output) {
    StatusCode status = accumulator.finish(bound.aggregates);
    if (status.isOk()) {
      status = having.evaluate(bound.command, accumulator, groupKeyRow, groupCount());
    }
    if (status.isOk() && having.matched()) {
      status = publisher.publish(block, accumulator, groupKeyRow, outputRow, true);
      if (status.isOk()) status = output.append(outputRow);
    }
    return status;
  }

  private int groupCount() {
    return bound.command.groupExpressionCount();
  }
}
