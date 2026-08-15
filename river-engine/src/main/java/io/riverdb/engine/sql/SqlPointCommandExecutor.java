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
  private boolean blockPipeline;

  SqlPointCommandExecutor(
      RelationalSession relationalSession,
      BoundSqlStatement boundStatement,
      SqlBinder statementBinder,
      SqlViewExpander viewExpander,
      SqlDmlExecutor dmlExecutor,
      SqlQueryExecution queryExecution,
      SqlBlockPlanBinder pipelineBinder,
      SqlRowProjectionEvaluator projectionEvaluator) {
    session = relationalSession;
    bound = boundStatement;
    binder = statementBinder;
    views = viewExpander;
    dml = dmlExecutor;
    queries = queryExecution;
    blockBinder = pipelineBinder;
    rowExpressions = projectionEvaluator;
  }

  StatusCode execute(SqlExecutionResult result) {
    blockPipeline = false;
    StatusCode status = isPointQuery() ? prepareQuery() : prepareMutation();
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
    return status;
  }

  private StatusCode prepareQuery() {
    if (bound.query.hasNestedTopology()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    StatusCode status = views.resolve(session, bound, binder);
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    blockPipeline = status.isOk() && bound.query.isBlockPipeline();
    if (blockPipeline) {
      status = blockBinder.bind(session, bound, rowExpressions);
    } else if (status.isOk()) {
      status = binder.bindQueryBlocks(session, bound);
    }
    if (blockPipeline) return status;
    return status.isOk()
        ? binder.bindDataCommand(bound.command, bound.query, bound) : status;
  }

  private StatusCode prepareMutation() {
    StatusCode status = session.resolveTable(bound.command.tableName(), bound.table);
    if (status.isOk()) {
      status = binder.bindDataCommand(bound.command, bound.query, bound);
    }
    return status.isOk() ? binder.captureExecutableQuery(bound) : status;
  }

  boolean isPointQuery() {
    return isPointQuery(bound.command.type());
  }

  int affectedRows() {
    return dml.affectedRows(bound.command);
  }

  boolean hasOpenResources() {
    return dml.hasOpenResources() || queries.hasPointResources();
  }

  StatusCode closeResources() {
    StatusCode status = dml.closeResources();
    return status.isOk() ? queries.closePointResources() : status;
  }

  private static boolean isPointQuery(SqlCommandType type) {
    return type == SqlCommandType.SELECT
        || type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || SqlBinder.isValueAggregate(type);
  }
}
