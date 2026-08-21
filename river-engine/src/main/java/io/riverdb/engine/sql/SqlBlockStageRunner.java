package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;

/** Activates and materializes the bounded block stages from deepest to root. */
final class SqlBlockStageRunner {
  private final BoundSqlStatement bound;
  private final SqlBlockPlanBinder binder;
  private final SqlRowProjectionEvaluator projections;
  private final SqlBlockSource source;
  private final SqlBlockJoinStage joinStage;
  private final SqlBlockStageProjector projector;
  private final SqlBlockProjectionStage projectionStage;
  private final SqlBlockRowStore first;
  private final SqlBlockRowStore second;
  private final SqlAggregateAccumulatorSet accumulator =
      new SqlAggregateAccumulatorSet();
  private final SqlHavingEvaluator having;
  private final SqlBlockAggregatePublisher publisher;
  private final SqlBlockScalarAggregateStage scalarStage;
  private final SqlBlockGroupedAggregateStage groupedStage;
  private SqlBlockRowStore finalStore;

  SqlBlockStageRunner(
      RelationalSession session,
      BoundSqlStatement statement,
      SqlBlockPlanBinder planBinder,
      SqlJoinChainSource joinSource,
      SqlExpressionEvaluator expressions,
      SqlBoundPredicateEvaluator predicates,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlTemporalContext temporal,
      SqlBlockRowStore firstStore,
      SqlBlockRowStore secondStore) {
    bound = statement;
    binder = planBinder;
    projections = projectionEvaluator;
    source = new SqlBlockSource(session, statement, joinSource, projectionEvaluator);
    joinStage = new SqlBlockJoinStage(
        statement, source, predicates, projectionEvaluator);
    projector = new SqlBlockStageProjector(
        statement, expressions, projectionEvaluator, temporal);
    projectionStage = new SqlBlockProjectionStage(statement, source, projector);
    first = firstStore;
    second = secondStore;
    having = new SqlHavingEvaluator(statement, expressions, temporal);
    publisher = new SqlBlockAggregatePublisher(statement);
    scalarStage = new SqlBlockScalarAggregateStage(
        statement, source, projector, having, accumulator, publisher);
    groupedStage = new SqlBlockGroupedAggregateStage(
        statement, having, accumulator, publisher);
  }

  StatusCode run(
      SqlBlockRow sourceRow,
      SqlBlockStagePlan plan) {
    SqlBoundBlockPlans plans = bound.blockPlans();
    SqlBlockRowStore input = null;
    StatusCode status = StatusCode.OK;
    for (int block = plans.count() - 1;
        status.isOk() && block >= 0; block--) {
      SqlBlockSchema child = block + 1 == plans.count()
          ? plans.baseSchema() : plans.schema(block + 1);
      status = binder.activate(bound, block, child);
      if (status.isOk()) status = prepareActive();
      SqlBlockRowStore output = input == first ? second : first;
      if (status.isOk()) status = execute(block, input, output, sourceRow);
      input = status.isOk() ? finalStore : input;
      if (status.isOk()) plan.setRows(block, stageRows(block));
    }
    finalStore = status.isOk() ? input : null;
    return status;
  }

  private StatusCode prepareActive() {
    if (bound.command.type() == SqlCommandType.JOIN_SCAN) {
      StatusCode status = joinStage.prepare();
      return status.isOk() ? having.prepare(bound.command) : status;
    }
    StatusCode status = projections.prepare(bound);
    if (status.isOk()) status = projector.prepare();
    return status.isOk() ? having.prepare(bound.command) : status;
  }

  private StatusCode execute(
      int block,
      SqlBlockRowStore input,
      SqlBlockRowStore output,
      SqlBlockRow sourceRow) {
    SqlCommandType type = bound.command.type();
    if (type == SqlCommandType.JOIN_SCAN) {
      StatusCode status = joinStage.materialize(block, output, sourceRow);
      finalStore = status.isOk() ? output : null;
      return status;
    }
    if (SqlBinder.isScalarAggregate(type)) {
      StatusCode status = scalarStage.execute(
          block, input, output, outputSortKey(block));
      finalStore = status.isOk() ? output : null;
      return status;
    }
    if (SqlBinder.isGroupAggregate(type)) {
      StatusCode status = projectionStage.materialize(block, input, output, 0);
      if (!status.isOk()) return status;
      SqlBlockRowStore grouped = alternate(input, output);
      status = groupedStage.execute(block, output, grouped, outputSortKey(block));
      finalStore = status.isOk() ? grouped : null;
      return status;
    }
    StatusCode status = projectionStage.materialize(block, input, output,
        type == SqlCommandType.DISTINCT_SCAN ? 0 : outputSortKey(block));
    if (!status.isOk()) return status;
    if (type != SqlCommandType.DISTINCT_SCAN) {
      finalStore = output;
      return StatusCode.OK;
    }
    SqlBlockRowStore distinct = alternate(input, output);
    status = projectionStage.deduplicate(block, output, distinct);
    finalStore = status.isOk() ? distinct : null;
    return status;
  }

  private int outputSortKey(int block) {
    return block == 0 && bound.command.isOrdered()
        ? bound.blockPlans().schema(block).find(bound.command.orderColumnName()) : -1;
  }

  private SqlBlockRowStore alternate(
      SqlBlockRowStore input, SqlBlockRowStore output) {
    SqlBlockRowStore alternate = input == null || input == first ? second : first;
    return alternate == output ? output == first ? second : first : alternate;
  }

  private long stageRows(int block) {
    if (finalStore == null) return 0;
    long count = finalStore.rowCount();
    return block == 0
        ? Math.min(count, bound.blockPlans().command(0).rowLimit()) : count;
  }

  SqlBlockRowStore finalStore() { return finalStore; }
  boolean hasResources() { return source.hasResources(); }

  StatusCode close() {
    StatusCode status = source.close();
    accumulator.clearAll();
    projector.reset();
    having.reset();
    projectionStage.reset();
    scalarStage.reset();
    groupedStage.reset();
    publisher.reset();
    finalStore = null;
    return status;
  }
}
