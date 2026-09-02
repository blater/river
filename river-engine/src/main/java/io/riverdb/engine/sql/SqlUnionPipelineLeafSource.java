package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlQuery;

/** Rebinds each UNION leaf into the established reusable block pipeline. */
final class SqlUnionPipelineLeafSource implements SqlUnionLeafSource {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlBinder binder;
  private final SqlBlockPlanBinder blockBinder;
  private final SqlSubqueryGraphExecution subqueries;
  private final SqlBoundPredicateEvaluator predicates;
  private final SqlRowProjectionEvaluator projections;
  private final SqlJoinChainSource joins;
  private final SqlBlockPipelineExecution pipeline;
  private SqlQuery query;
  private int currentBlock = -1;

  SqlUnionPipelineLeafSource(
      RelationalSession session,
      SqlBinder sharedBinder,
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget budget) {
    this.session = session;
    bound = new BoundSqlStatement(budget);
    binder = sharedBinder;
    blockBinder = new SqlBlockPlanBinder(temporal, sharedBinder, budget);
    projections = new SqlRowProjectionEvaluator(expressions, temporal, budget);
    subqueries = new SqlSubqueryGraphExecution(
        session, bound, expressions, temporal, budget);
    predicates = new SqlBoundPredicateEvaluator(
        bound, expressions, subqueries, temporal, budget);
    joins = new SqlJoinChainSource(session, expressions, budget);
    pipeline = new SqlBlockPipelineExecution(
        session, bound, blockBinder, joins, new SqlJoinChainPlan(),
        expressions, predicates, subqueries, projections, temporal, budget);
  }

  StatusCode prepare(SqlQuery setQuery) {
    StatusCode status = close(StatusCode.OK);
    if (!status.isOk()) return status;
    if (setQuery == null || !setQuery.hasSetExpression()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    query = setQuery;
    return StatusCode.OK;
  }

  @Override
  public StatusCode describe(int block, SqlBlockSchema destination) {
    if (destination == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = bind(block);
    if (status.isOk()) destination.copyFrom(schema());
    return status.isOk() ? destination.status() : status;
  }

  @Override
  public StatusCode open(int block) {
    StatusCode status = currentBlock == block ? StatusCode.OK : bind(block);
    if (status.isOk() && bound.executableQuery.edgeCount() > 0
        && bound.executableQuery.root().joinChain() != null) {
      subqueries.registerExternalJoinSource(0, joins);
    }
    if (status.isOk() && bound.executableQuery.edgeCount() > 0) {
      status = subqueries.prepare();
    }
    return status.isOk() ? pipeline.prepare() : status;
  }

  @Override public SqlBlockSchema schema() {
    return currentBlock < 0 ? null : bound.blockPlans().schema(0);
  }
  @Override public boolean finalized() { return true; }
  @Override public StatusCode next(SqlBlockRow destination) {
    return pipeline.nextRow(destination);
  }

  @Override
  public StatusCode close(StatusCode runtimeStatus) {
    StatusCode pipelineStatus = pipeline.close();
    StatusCode subqueryStatus = subqueries.close();
    subqueries.clearExternalJoinSource();
    predicates.reset();
    projections.reset();
    currentBlock = -1;
    if (!runtimeStatus.isOk()) return runtimeStatus;
    return pipelineStatus.isOk() ? subqueryStatus : pipelineStatus;
  }

  private StatusCode bind(int block) {
    if (query == null || block < 0 || block >= query.blockCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = close(StatusCode.OK);
    if (!status.isOk()) return status;
    bound.reset();
    status = query.copySetLeafQuery(block, bound.query, bound.command);
    if (status.isOk()) status = bound.query.promoteRootBlockPipeline(bound.command);
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    if (status.isOk()) status = blockBinder.bind(session, bound, projections);
    if (status.isOk()) binder.publishExecutableQuery(bound);
    if (status.isOk()) currentBlock = block;
    return status;
  }

}
