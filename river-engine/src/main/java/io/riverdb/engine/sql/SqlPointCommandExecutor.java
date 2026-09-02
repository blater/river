package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;

/** Owns the complete body of one non-streaming data command. */
final class SqlPointCommandExecutor {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlBinder binder;
  private final SqlViewExpander views;
  private final SqlDmlExecutor dml;
  private final SqlQueryExecution queries;
  private final SqlBlockPlanBinder blockBinder;
  private final SqlRowProjectionEvaluator rowExpressions;
  private final SqlDescriptorPointExecution descriptorExecution;
  private final SqlPointDescriptorViewPreparation descriptorPreparation;
  private boolean blockPipeline;
  private boolean descriptorBacked;
  private long catalogGeneration;

  SqlPointCommandExecutor(
      RelationalSession relationalSession,
      BoundSqlStatement boundStatement,
      SqlBinder statementBinder,
      SqlViewExpander viewExpander,
      SqlDmlExecutor dmlExecutor,
      SqlQueryExecution queryExecution,
      SqlBlockPlanBinder pipelineBinder,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    session = relationalSession;
    bound = boundStatement;
    binder = statementBinder;
    views = viewExpander;
    dml = dmlExecutor;
    queries = queryExecution;
    blockBinder = pipelineBinder;
    rowExpressions = projectionEvaluator;
    descriptorExecution = new SqlDescriptorPointExecution(
        relationalSession, projectionEvaluator, queries.predicateEvaluator(),
        temporal, shapeBudget);
    descriptorPreparation = new SqlPointDescriptorViewPreparation(
        relationalSession, boundStatement, viewExpander, descriptorExecution);
  }

  StatusCode execute(SqlExecutionResult result) {
    blockPipeline = false;
    descriptorBacked = false;
    catalogGeneration = 0;
    StatusCode status = descriptorPreparation.prepare();
    if (status.isOk() && bound.query.hasNestedTopology()) {
      status = descriptorExecution.close();
      if (status.isOk()) status = bound.query.promoteRootBlockPipeline(bound.command);
      return status.isOk() ? executePromoted(result, true) : finish(status);
    }
    boolean boundPredicate = status.isOk()
        && SqlDescriptorExpressionRouting.mutationPredicateRequired(bound.command);
    if (boundPredicate) {
      status = descriptorExecution.prepareBinding(bound.table);
      if (status.isOk()) {
        status = binder.bindDataCommand(bound.command, bound.query, bound);
      }
      if (status.isOk()) status = binder.captureExecutableQuery(bound);
      if (status.isOk()) queries.adoptPreparedQuery();
      if (status.isOk()) status = queries.prepareProjectionPrograms();
      if (status.isOk()) status = descriptorExecution.prepareBoundPredicate();
    }
    if (status.isOk() && !boundPredicate
        && (bound.command.type() == SqlCommandType.INSERT
            || bound.command.type() == SqlCommandType.UPDATE)
        && bound.command.mutationExpressionCount() > 0) {
      status = descriptorExecution.prepareBinding(bound.table);
      if (status.isOk()) {
        status = binder.bindDescriptorMutationExpressions(
            bound.command, bound, bound.command.type() == SqlCommandType.UPDATE);
      }
      if (status.isOk()) status = rowExpressions.prepare(bound);
    }
    if (status == StatusCode.CONFLICT
        && bound.command.type() == SqlCommandType.JOIN_SCAN
        && bound.command.isOrdered()) {
      status = bound.query.promoteRootBlockPipeline(bound.command);
      return status.isOk() ? executePromoted(result) : finish(status);
    }
    if (status.isOk() && (bound.query.isBlockPipeline()
        || SqlDescriptorExpressionRouting.required(bound.command)
        || !boundPredicate
            && SqlDescriptorExpressionRouting.predicateRequired(bound.command))) {
      status = descriptorExecution.close();
      if (status.isOk() && !bound.query.isBlockPipeline()) {
        status = bound.query.promoteRootBlockPipeline(bound.command);
      }
      return status.isOk() ? executePromoted(result) : finish(status);
    }
    return status == StatusCode.CONFLICT
        ? executeLegacy(result) : executeDescriptor(status, result);
  }

  private StatusCode executeDescriptor(
      StatusCode status, SqlExecutionResult result) {
    descriptorBacked = status.isOk();
    if (status.isOk()) status = descriptorExecution.execute(bound.command, result);
    return finish(status);
  }

  private StatusCode executeLegacy(SqlExecutionResult result) {
    StatusCode status = isPointQuery()
        ? prepareQuery(!bound.expandedView) : prepareMutation();
    if (status.isOk()) {
      queries.adoptPreparedQuery();
    }
    if (status.isOk() && !blockPipeline) {
      status = queries.prepareProjectionPrograms();
    }
    if (status.isOk()) {
      status = blockPipeline
          ? queries.executeBlockPipeline(result)
          : dml.handles(bound.command.type())
          ? dml.execute(bound.command, bound, result)
          : queries.executePointQuery(result);
    }
    return finish(status);
  }

  private StatusCode executePromoted(SqlExecutionResult result) {
    return executePromoted(result, false);
  }

  private StatusCode executePromoted(SqlExecutionResult result, boolean acceptFirstRow) {
    StatusCode status = prepareQuery(false);
    if (status.isOk()) queries.adoptPreparedQuery();
    if (status.isOk()) status = queries.executeBlockPipeline(result, acceptFirstRow);
    return finish(status);
  }

  private StatusCode finish(StatusCode status) {
    if (status.isOk()) catalogGeneration = session.catalogGeneration();
    StatusCode finished = queries.finishPointStatement();
    return status.isOk() ? finished : status;
  }

  long catalogGeneration() { return catalogGeneration; }

  private StatusCode prepareQuery() {
    return prepareQuery(true);
  }

  private StatusCode prepareQuery(boolean resolveViews) {
    StatusCode status = resolveViews ? views.resolve(session, bound) : StatusCode.OK;
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    blockPipeline = status.isOk() && bound.query.isBlockPipeline();
    if (blockPipeline) {
      status = blockBinder.bind(session, bound, rowExpressions);
    } else if (status.isOk()) {
      status = binder.bindQueryBlocks(session, bound);
    }
    if (blockPipeline) return status;
    if (status.isOk()) status = binder.bindDataCommand(bound.command, bound.query, bound);
    return status;
  }

  private StatusCode prepareMutation() {
    StatusCode status = session.resolveTable(bound.command.tableName(), bound.table);
    if (status.isOk()) {
      status = binder.bindDataCommand(bound.command, bound.query, bound);
    }
    return status.isOk() ? binder.captureExecutableQuery(bound) : status;
  }

  boolean isPointQuery() {
    return bound.query.isBlockPipeline() || isPointQuery(bound.command.type());
  }

  int affectedRows() {
    if (descriptorBacked) return descriptorExecution.affectedRows();
    return dml.affectedRows(bound.command);
  }

  boolean hasOpenResources() {
    return descriptorExecution.hasResources()
        || dml.hasOpenResources() || queries.hasPointResources();
  }

  StatusCode closeResources() {
    StatusCode status = descriptorExecution.close();
    StatusCode dmlStatus = dml.closeResources();
    if (status.isOk()) status = dmlStatus;
    StatusCode queryStatus = queries.closePointResources();
    return status.isOk() ? queryStatus : status;
  }

  private static boolean isPointQuery(SqlCommandType type) {
    return type == SqlCommandType.SELECT
        || type == SqlCommandType.SCAN
        || type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || type == SqlCommandType.COUNT_DISTINCT
        || SqlBinder.isValueAggregate(type);
  }
}
