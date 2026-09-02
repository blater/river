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
  private final SqlAggregateAccumulatorSet accumulator;
  private final SqlHavingEvaluator having;
  private final SqlBlockAggregatePublisher publisher;
  private final SqlBlockScalarAggregateStage scalarStage;
  private final SqlBlockGroupedAggregateStage groupedStage;
  private SqlBlockRowStore finalStore;
  private boolean fusedScalarJoin;
  private int fusedJoinBlock = -1;

  SqlBlockStageRunner(
      RelationalSession session,
      BoundSqlStatement statement,
      SqlBlockPlanBinder planBinder,
      SqlJoinChainSource joinSource,
      SqlExpressionEvaluator expressions,
      SqlBoundPredicateEvaluator predicates,
      SqlSubqueryGraphExecution subqueries,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget,
      SqlBlockRowStore firstStore,
      SqlBlockRowStore secondStore) {
    bound = statement;
    binder = planBinder;
    projections = projectionEvaluator;
    source = new SqlBlockSource(
        session, statement, joinSource, predicates, subqueries, projectionEvaluator);
    SqlBlockOutputOrder outputOrder = new SqlBlockOutputOrder();
    joinStage = new SqlBlockJoinStage(
        statement,
        source,
        subqueries,
        projectionEvaluator,
        session,
        expressions,
        temporal,
        shapeBudget,
        outputOrder);
    projector = new SqlBlockStageProjector(
        statement, expressions, projectionEvaluator, temporal, shapeBudget);
    projectionStage = new SqlBlockProjectionStage(
        statement, source, projector, outputOrder);
    first = firstStore;
    second = secondStore;
    accumulator = new SqlAggregateAccumulatorSet(shapeBudget);
    having = new SqlHavingEvaluator(statement, expressions, temporal, shapeBudget);
    publisher = new SqlBlockAggregatePublisher(statement);
    scalarStage = new SqlBlockScalarAggregateStage(
        statement, source, projector, having, accumulator, publisher, outputOrder);
    groupedStage = new SqlBlockGroupedAggregateStage(
        statement, having, accumulator, publisher, outputOrder);
  }

  StatusCode run(
      SqlBlockRow sourceRow,
      SqlBlockStagePlan plan) {
    SqlBoundBlockPlans plans = bound.blockPlans();
    SqlBlockRowStore input = null;
    StatusCode status = prepareFusedScalarJoin(plans);
    for (int block = plans.count() - 1;
        status.isOk() && block >= 0; block--) {
      SqlBlockSchema child = block + 1 == plans.count()
          ? plans.baseSchema() : plans.schema(block + 1);
      status = binder.activate(bound, block, child);
      if (status.isOk()) status = prepareActive(block);
      SqlBlockRowStore output = input == first ? second : first;
      if (status.isOk()) status = execute(block, input, output, sourceRow);
      if (status.isOk() && finalStore != null) {
        status = finalStore.limit(plans.command(block).rowLimit());
      }
      if (status.isOk() && block == plans.count() - 1
          && bound.command.type() != SqlCommandType.JOIN_SCAN) {
        plan.setRootAccess(source.accessColumn());
      }
      input = status.isOk() ? finalStore : input;
      if (status.isOk()) plan.setRows(block, stageRows(block));
    }
    finalStore = status.isOk() ? input : null;
    return status;
  }

  private StatusCode prepareActive(int block) {
    if (bound.command.type() == SqlCommandType.JOIN_SCAN) {
      StatusCode status = joinStage.prepare(block);
      if (!status.isOk()) return status;
      return fusedScalarJoin && block == fusedJoinBlock
          ? having.prepare(bound.command)
          : having.prepare(bound.command, accumulator, bound.aggregates);
    }
    StatusCode status = projections.prepare(bound);
    if (status.isOk()) status = projector.prepare(block);
    if (!status.isOk()) return status;
    return fusedScalarJoin && block == fusedJoinBlock - 1
        ? having.prepare(bound.command)
        : having.prepare(bound.command, accumulator, bound.aggregates);
  }

  private StatusCode execute(
      int block,
      SqlBlockRowStore input,
      SqlBlockRowStore output,
      SqlBlockRow sourceRow) {
    SqlCommandType type = bound.command.type();
    if (type == SqlCommandType.JOIN_SCAN) {
      return executeJoin(block, input, output, sourceRow);
    }
    if (SqlBinder.isScalarAggregate(type)) {
      StatusCode status = fusedScalarJoin && block == fusedJoinBlock - 1
          ? scalarStage.publishAccumulated(block, output)
          : scalarStage.execute(block, input, output);
      finalStore = status.isOk() ? output : null;
      return status;
    }
    if (SqlBinder.isGroupAggregate(type)) {
      StatusCode status = projectionStage.materialize(block, input, output);
      if (!status.isOk()) return status;
      SqlBlockRowStore grouped = alternate(input, output);
      status = groupedStage.execute(block, output, grouped);
      finalStore = status.isOk() ? grouped : null;
      return status;
    }
    StatusCode status = projectionStage.materialize(block, input, output);
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

  private StatusCode executeJoin(
      int block,
      SqlBlockRowStore input,
      SqlBlockRowStore output,
      SqlBlockRow sourceRow) {
    if (fusedScalarJoin && block == fusedJoinBlock) {
      StatusCode status = joinStage.accumulateScalar(
          accumulator, bound.joinedAggregates, sourceRow);
      finalStore = null;
      return status;
    }
    StatusCode status = joinStage.materialize(block, output, sourceRow);
    if (!status.isOk() || bound.command.aggregateInvocationCount() == 0) {
      finalStore = status.isOk() ? output : null;
      return status;
    }
    return aggregateJoin(block, input, output);
  }

  private StatusCode aggregateJoin(
      int block, SqlBlockRowStore input, SqlBlockRowStore operands) {
    SqlBlockRowStore output = alternate(input, operands);
    StatusCode status = bound.command.groupExpressionCount() > 0
        ? groupedStage.execute(block, operands, output)
        : scalarStage.executeJoined(block, operands, output);
    finalStore = status.isOk() ? output : null;
    return status;
  }

  private SqlBlockRowStore alternate(
      SqlBlockRowStore input, SqlBlockRowStore output) {
    SqlBlockRowStore alternate = input == null || input == first ? second : first;
    return alternate == output ? output == first ? second : first : alternate;
  }

  private long stageRows(int block) {
    if (fusedScalarJoin && block == fusedJoinBlock) return joinStage.acceptedRows();
    if (finalStore == null) return 0;
    long count = finalStore.rowCount();
    return block == 0
        ? Math.min(count, bound.blockPlans().command(0).rowLimit()) : count;
  }

  private StatusCode prepareFusedScalarJoin(SqlBoundBlockPlans plans) {
    fusedScalarJoin = false;
    fusedJoinBlock = -1;
    if (!SqlScalarJoinFusionPolicy.admits(bound, plans)) return StatusCode.OK;
    StatusCode status = bound.joinedAggregates.copyDirectFrom(
        bound.aggregates, bound.projectionPrograms);
    if (status.isOk()) {
      status = SqlAggregateAccumulatorCapacity.reserve(
          accumulator, bound.joinedAggregates);
    }
    if (status.isOk()) {
      fusedScalarJoin = true;
      fusedJoinBlock = 1;
    }
    return status;
  }

  SqlBlockRowStore finalStore() { return finalStore; }
  boolean hasResources() { return source.hasResources(); }

  StatusCode close() {
    StatusCode status = joinStage.close();
    StatusCode sourceStatus = source.close();
    if (status.isOk()) status = sourceStatus;
    StatusCode distinct = accumulator.closeDistinct();
    if (status.isOk()) status = distinct;
    accumulator.clearAll();
    projector.reset();
    having.reset();
    projectionStage.reset();
    scalarStage.reset();
    groupedStage.reset();
    publisher.reset();
    finalStore = null;
    fusedScalarJoin = false;
    fusedJoinBlock = -1;
    return status;
  }
}
