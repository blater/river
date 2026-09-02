package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;

/** Binds concrete streaming query families after routing has selected one. */
final class SqlStreamingQueryBinder {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlBinder binder;
  private final SqlQueryExecution queries;
  private final SqlBlockPlanBinder blockBinder;
  private final SqlRowProjectionEvaluator rowExpressions;
  private final SqlTemporalContext temporal;

  SqlStreamingQueryBinder(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlBinder sqlBinder,
      SqlQueryExecution queryExecution,
      SqlBlockPlanBinder pipelineBinder,
      SqlRowProjectionEvaluator projections,
      SqlTemporalContext temporalContext) {
    session = relationalSession;
    bound = statement;
    binder = sqlBinder;
    queries = queryExecution;
    blockBinder = pipelineBinder;
    rowExpressions = projections;
    temporal = temporalContext;
  }

  StatusCode universalJoin() {
    SqlBoundJoinContext context = bound.existingJoinContext(0);
    StatusCode status = context == null
        ? StatusCode.CORRUPTION : binder.bindJoin(bound.command, bound, context);
    if (status.isOk()) status = queries.prepareProjectionPrograms();
    if (status.isOk()) status = queries.configureUniversalJoin();
    return publish(status);
  }

  StatusCode promoteBlockPipeline() {
    StatusCode status = bound.query.promoteRootBlockPipeline(bound.command);
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    return status.isOk() ? blockPipeline() : status;
  }

  StatusCode blockPipeline() {
    if (!bound.query.isBlockPipeline()) return StatusCode.CORRUPTION;
    StatusCode status = blockBinder.bind(
        session, bound, queries.explainOnly() ? null : rowExpressions);
    if (status.isOk() && queries.explainOnly()) {
      status = temporal.validateZones(bound.command, bound.query);
    }
    if (status.isOk()) status = queries.prepareBlockPipeline();
    return publish(status);
  }

  StatusCode join() {
    SqlBoundJoinContext context = bound.joinContext(0);
    StatusCode status = binder.resolveJoinRoles(
        session, bound.command, context, bound.table, false);
    if (status.isOk()) status = binder.bindQueryBlocks(session, bound);
    if (status.isOk()) {
      status = bound.executableQuery.edgeCount() == 0
          ? binder.bindJoin(bound.command, bound, context)
          : binder.bindJoinProjection(bound.command, bound, context);
    }
    if (status.isOk() && bound.command.isOrdered()) {
      status = binder.bindJoinOrder(bound.command, bound);
    }
    if (status.isOk()) status = queries.configureJoin();
    if (status.isOk()) status = queries.explainOnly()
        ? temporal.validateZones(bound.command, bound.query)
        : queries.prepareProjectionPrograms();
    return publish(status);
  }

  StatusCode group() {
    StatusCode status = bindRoot();
    if (status.isOk()) status = binder.bindGroupAggregate(
        bound.command, bound.query, bound);
    if (status.isOk()) status = queries.prepareProjectionPrograms();
    return publish(status);
  }

  StatusCode distinct() {
    StatusCode status = bindRoot();
    if (status.isOk()) status = binder.bindDistinct(bound.command, bound.query, bound);
    if (status.isOk()) status = queries.prepareProjectionPrograms();
    return publish(status);
  }

  StatusCode data(SqlCommandType type) {
    StatusCode status = bindRoot();
    if (status.isOk()) status = binder.bindDataCommand(bound.command, bound.query, bound);
    if (status.isOk() && bound.command.isOrdered()) {
      status = binder.bindOrder(bound.command, bound);
    }
    if (status.isOk()) status = queries.prepareProjectionPrograms();
    return publish(status);
  }

  private StatusCode bindRoot() { return binder.bindQueryBlocks(session, bound); }

  private StatusCode publish(StatusCode status) {
    if (status.isOk()) binder.publishExecutableQuery(bound);
    return status;
  }
}
