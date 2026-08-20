package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Reduces one group-sorted operand store into finalized aggregate rows. */
final class SqlBlockGroupedAggregateStage {
  private final BoundSqlStatement bound;
  private final SqlHavingEvaluator having;
  private final SqlAggregateAccumulatorSet accumulator;
  private final SqlBlockAggregatePublisher publisher;
  private final SqlBlockRow sourceRow = new SqlBlockRow();
  private final SqlBlockRow lookaheadRow = new SqlBlockRow();
  private final SqlBlockRow groupKeyRow = new SqlBlockRow();
  private final SqlBlockRow outputRow = new SqlBlockRow();

  SqlBlockGroupedAggregateStage(
      BoundSqlStatement statement,
      SqlHavingEvaluator havingEvaluator,
      SqlAggregateAccumulatorSet aggregateAccumulator,
      SqlBlockAggregatePublisher aggregatePublisher) {
    bound = statement;
    having = havingEvaluator;
    accumulator = aggregateAccumulator;
    publisher = aggregatePublisher;
  }

  StatusCode execute(
      int block,
      SqlBlockRowStore sorted,
      SqlBlockRowStore output,
      int outputSortKey) {
    StatusCode status = output.begin(
        bound.blockPlans().schema(block), outputSortKey,
        bound.command.isDescendingOrder());
    boolean lookahead = false;
    while (status.isOk()) {
      status = beginGroup(sorted, lookahead);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      lookahead = false;
      groupKeyRow.copyFrom(sourceRow);
      accumulator.reset(bound.aggregates);
      do {
        status = accumulator.accumulateBlock(bound.aggregates, sourceRow);
        if (!status.isOk()) break;
        status = sorted.next(lookaheadRow);
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.OK;
          break;
        }
        if (!status.isOk()) break;
        lookahead = !sameKey(groupKeyRow, lookaheadRow);
        if (!lookahead) sourceRow.copyFrom(lookaheadRow);
      } while (!lookahead);
      if (status.isOk()) status = publish(block, output);
    }
    StatusCode closed = sorted.close();
    if (status.isOk()) status = closed;
    return status.isOk() ? output.finish() : status;
  }

  private StatusCode beginGroup(SqlBlockRowStore sorted, boolean lookahead) {
    if (lookahead) {
      sourceRow.copyFrom(lookaheadRow);
      return StatusCode.OK;
    }
    return sorted.next(sourceRow);
  }

  private StatusCode publish(int block, SqlBlockRowStore output) {
    StatusCode status = accumulator.finish(bound.aggregates);
    int groupLength = status.isOk()
        ? publisher.encodeGroupKey(bound.blockPlans().operandSchema(block), groupKeyRow) : -1;
    if (status.isOk() && groupLength < 0) status = StatusCode.CORRUPTION;
    if (status.isOk()) status = having.evaluate(
        bound.command,
        bound.havingPrograms,
        accumulator,
        groupKeyRow.value(0),
        groupKeyRow.nullValue(0),
        publisher.groupText(),
        groupLength);
    if (status.isOk() && having.matched()) {
      status = publisher.publish(block, accumulator, groupKeyRow, outputRow, true);
      if (status.isOk()) status = output.append(outputRow);
    }
    return status;
  }

  void reset() {
    sourceRow.reset(0);
    lookaheadRow.reset(0);
    groupKeyRow.reset(0);
    outputRow.reset(0);
  }

  private static boolean sameKey(SqlBlockRow left, SqlBlockRow right) {
    if (left.nullValue(0) != right.nullValue(0)) return false;
    if (left.nullValue(0)) return true;
    if (left.textLength(0) != right.textLength(0)) return false;
    if (left.textLength(0) == 0) return left.value(0) == right.value(0);
    for (int index = 0; index < left.textLength(0); index++) {
      if (left.textCharacter(0, index) != right.textCharacter(0, index)) return false;
    }
    return true;
  }
}
