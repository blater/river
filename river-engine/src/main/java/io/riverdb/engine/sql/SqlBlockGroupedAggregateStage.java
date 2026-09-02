package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Reduces one group-sorted operand store into finalized aggregate rows. */
final class SqlBlockGroupedAggregateStage {
  private final SqlBlockGroupedAggregateExecution execution;

  SqlBlockGroupedAggregateStage(
      BoundSqlStatement statement,
      SqlHavingEvaluator havingEvaluator,
      SqlAggregateAccumulatorSet aggregateAccumulator,
      SqlBlockAggregatePublisher aggregatePublisher,
      SqlBlockOutputOrder outputOrder) {
    execution = new SqlBlockGroupedAggregateExecution(
        statement, havingEvaluator, aggregateAccumulator, aggregatePublisher, outputOrder);
  }

  StatusCode execute(
      int block,
      SqlBlockRowStore sorted,
      SqlBlockRowStore output) {
    return execution.execute(block, sorted, output);
  }

  void reset() {
    execution.reset();
  }
}
