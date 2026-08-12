package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlParser;
import io.riverdb.tx.api.IsolationLevel;

/** Owns the public session operation gate and delegates to concrete executors. */
final class SqlSessionExecutionCoordinator {
  private final RelationalSession session;
  private final SqlParser parser = new SqlParser();
  private final BoundSqlStatement bound = new BoundSqlStatement();
  private final SqlBinder binder = new SqlBinder();
  private final SqlExpressionEvaluator expressions = new SqlExpressionEvaluator();
  private final SqlViewExpander viewExpander = new SqlViewExpander();
  private final SqlViewDefinitionValidator viewValidator =
      new SqlViewDefinitionValidator();
  private final SqlTransactionState transactions;
  private final SqlCommandDispatcher dispatcher;
  private final SqlDmlExecutor dml;
  private final SqlPointCommandExecutor pointCommands;
  private final SqlStreamingStatementLifecycle streaming;
  private final SqlAtomicStatementLifecycle atomic;
  private final SqlQueryExecution queries;
  private boolean closed;

  SqlSessionExecutionCoordinator(
      RelationalDatabase database, RelationalSession session) {
    this.session = session;
    transactions = new SqlTransactionState(session);
    dispatcher = new SqlCommandDispatcher(database, session, transactions);
    dml = new SqlDmlExecutor(database, session, expressions);
    pointCommands = new SqlPointCommandExecutor(
        session, bound, binder, dml);
    streaming = new SqlStreamingStatementLifecycle(session, transactions);
    atomic = new SqlAtomicStatementLifecycle(session, transactions);
    queries = new SqlQueryExecution(
        session,
        bound,
        expressions);
    pointCommands.attachQueries(queries);
  }

  StatusCode execute(String sql, SqlExecutionResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (closed) {
      return StatusCode.CLOSED;
    }
    StatusCode cleanup = retryPendingCleanup();
    if (!cleanup.isOk()) {
      return cleanup;
    }
    if (queries.hasActiveScan()) {
      return StatusCode.CONFLICT;
    }
    if (transactions.isExplicit()) {
      result.setTransaction(true, session.visibleCommitSequence());
    }
    bound.reset();
    StatusCode status = parser.parse(sql, bound.command);
    if (status.isOk()) {
      status = binder.captureExecutableQuery(bound);
    }
    if (!status.isOk()) {
      return status;
    }
    if (dispatcher.handles(bound.command.type())) {
      return dispatcher.execute(bound.command, viewValidator, atomic, result);
    }
    return executePoint(result);
  }

  StatusCode beginScan(String sql, SqlScanCursor cursor) {
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (closed) {
      return StatusCode.CLOSED;
    }
    StatusCode cleanup = retryPendingCleanup();
    if (!cleanup.isOk()) {
      return cleanup;
    }
    if (queries.hasActiveScan() || cursor != null && cursor.isActive()) {
      return StatusCode.CONFLICT;
    }
    bound.reset();
    StatusCode status = parser.parseQuery(
        sql, bound.query, bound.command);
    if (status.isOk()) {
      status = binder.captureExecutableQuery(bound);
    }
    if (status.isOk() && !isQueryCommand(bound.command.type())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean explainOnly = bound.query.isExplain() && !bound.query.isAnalyze();
    boolean sequence = bound.command.type()
        == io.riverdb.sql.SqlCommandType.NEXT_SEQUENCE_VALUE;
    boolean scalar = SqlBinder.isScalarAggregate(bound.command.type());
    boolean preexecuted = status.isOk()
        && (!explainOnly && scalar || !bound.query.isExplain() && sequence);
    if (preexecuted) {
      queries.aggregateExecution().reset();
      status = sequence
          ? dispatcher.execute(
              bound.command, viewValidator, atomic, queries.aggregateExecution())
          : executePoint(queries.aggregateExecution());
    }
    if (!status.isOk()) {
      return status;
    }
    status = queries.initializeScan();
    if (!status.isOk()) {
      return status;
    }
    if (preexecuted) {
      status = queries.beginScan(cursor);
      if (!status.isOk() || !bound.query.isAnalyze()) {
        return status;
      }
      queries.describeCurrentPlan(cursor);
      status = queries.drainAnalyze(cursor);
      if (status.isOk()) {
        queries.completeScan(cursor);
      }
      return status.isOk()
          ? queries.claimExplainResult(cursor, queries.aggregateExecution(), true)
          : status;
    }
    status = streaming.begin();
    if (!status.isOk()) {
      return status;
    }
    status = prepareStreamingQuery();
    if (status.isOk()) {
      status = binder.captureExecutableQuery(bound);
      queries.refreshPreparedCommand();
    }
    if (!status.isOk()) {
      return failStreamingStart(status);
    }
    if (queries.explainOnly()
        && scalar) {
      queries.aggregateExecution().reset();
      queries.configureScalarAggregateExplain();
      status = streaming.finish(StatusCode.OK, queries.aggregateExecution());
      return status.isOk()
          ? queries.claimExplainResult(cursor, queries.aggregateExecution(), false)
          : status;
    }
    status = queries.beginScan(cursor);
    if (!status.isOk()) {
      return failStreamingStart(status);
    }
    if (queries.explainOnly()) {
      queries.describeCurrentPlan(cursor);
      return finishExplain(cursor, false, StatusCode.OK);
    }
    if (bound.query.isAnalyze()) {
      queries.describeCurrentPlan(cursor);
      status = queries.drainAnalyze(cursor);
      return finishExplain(cursor, true, status);
    }
    return StatusCode.OK;
  }

  StatusCode nextScan(SqlScanCursor cursor, SqlScanRowResult result) {
    if (closed) {
      return StatusCode.CLOSED;
    }
    return queries.nextScan(cursor, result);
  }

  CharSequence scanColumnName(SqlScanCursor cursor, int index) {
    if (closed) {
      return null;
    }
    return queries.scanColumnName(cursor, index);
  }

  int scanColumnTypeDescriptor(SqlScanCursor cursor, int index) {
    if (closed) {
      return 0;
    }
    return queries.scanColumnTypeDescriptor(cursor, index);
  }

  StatusCode closeScan(SqlScanCursor cursor, SqlExecutionResult result) {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (cursor == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (queries.syntheticScan()) {
      return queries.closeSyntheticScan(cursor, result);
    }
    result.reset();
    StatusCode status = queries.closePhysicalScan(cursor);
    boolean physicalCleanupComplete = status.isOk();
    status = streaming.finish(status, physicalCleanupComplete, result);
    if (physicalCleanupComplete && !streaming.isActive()) {
      queries.completeScan(cursor);
    }
    return status;
  }

  StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (queries.hasActiveScan()) {
      return StatusCode.CONFLICT;
    }
    StatusCode cleanup = retryPendingCleanup();
    if (!cleanup.isOk()) {
      return cleanup;
    }
    StatusCode status = transactions.isExplicit()
        ? transactions.abortExplicit() : StatusCode.OK;
    if (status.isOk()) {
      closed = true;
    }
    return status;
  }

  private StatusCode executePoint(SqlExecutionResult result) {
    StatusCode status = atomic.begin(IsolationLevel.READ_COMMITTED);
    boolean began = status.isOk();
    boolean implicit = began && atomic.implicit();
    if (status.isOk()) {
      status = pointCommands.execute(result);
    }
    boolean select = pointCommands.isPointQuery();
    if (began) {
      status = atomic.finish(status);
    }
    if (status.isOk()) {
      long commitSequence = implicit
          ? transactions.commitSequence() : session.visibleCommitSequence();
      if (select) {
        result.setCommitSequence(commitSequence);
      } else {
        result.setUpdate(
            pointCommands.affectedRows(), implicit ? commitSequence : 0);
      }
      result.setTransaction(
          transactions.isExplicit(), select ? commitSequence : implicit ? commitSequence : 0);
    }
    return status;
  }

  private StatusCode retryPendingCleanup() {
    StatusCode status = pointCommands.closeResources();
    if (status.isOk() && atomic.isActive()) {
      status = atomic.retry();
    }
    if (status.isOk() && streaming.isActive() && !queries.hasActiveScan()) {
      status = queries.retryFailedStartCleanup();
      boolean complete = status.isOk();
      status = streaming.failStart(status, complete);
      if (complete && !streaming.isActive()) {
        queries.completeFailedStart();
      }
    }
    return status;
  }

  private StatusCode prepareStreamingQuery() {
    SqlCommandType type = bound.command.type();
    StatusCode status = StatusCode.OK;
    if (type == SqlCommandType.SHOW_TABLES
        || type == SqlCommandType.SHOW_INDEXES) {
      return status;
    }
    if (type == SqlCommandType.JOIN_SCAN) {
      status = session.resolveTable(bound.command.tableName(), bound.table);
      if (status.isOk()) {
        status = session.resolveTable(bound.command.joinTableName(), bound.joinTable);
      }
      return status.isOk() ? binder.bindJoin(bound.command, bound) : status;
    }
    if (SqlBinder.isGroupAggregate(type) || type == SqlCommandType.DISTINCT_SCAN) {
      status = session.resolveTable(bound.command.tableName(), bound.table);
      return status.isOk()
          ? binder.bindPredicates(
              bound.command,
              bound.query,
              bound,
              false,
              false,
              false,
              false,
              false)
          : status;
    }
    if (queries.explainOnly() && SqlBinder.isScalarAggregate(type)) {
      status = session.resolveTable(bound.command.tableName(), bound.table);
    } else {
      status = viewExpander.resolve(session, bound);
      if (status.isOk()) {
        status = binder.captureExecutableQuery(bound);
      }
      queries.refreshPreparedCommand();
      if (status.isOk()) {
        status = queries.prepareNested();
      }
    }
    if (status.isOk()) {
      status = binder.bindDataCommand(
          bound.command,
          bound.query,
          bound,
          queries.correlatedScalar(),
          queries.correlatedNestedChain(),
          queries.recursiveNestedChain(),
          queries.recursiveRootCorrelated());
    }
    if (status.isOk() && bound.command.isOrdered()) {
      int orderColumn = binder.resolveOrderColumn(bound.command, bound);
      if (orderColumn < 0) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      } else {
        queries.setPreparedOrderColumn(orderColumn);
      }
    }
    return status;
  }

  private static boolean isQueryCommand(SqlCommandType type) {
    return type == SqlCommandType.SHOW_TABLES
        || type == SqlCommandType.SHOW_INDEXES
        || type == SqlCommandType.SCAN
        || type == SqlCommandType.SELECT
        || type == SqlCommandType.DISTINCT_SCAN
        || type == SqlCommandType.JOIN_SCAN
        || type == SqlCommandType.NEXT_SEQUENCE_VALUE
        || SqlBinder.isScalarAggregate(type)
        || SqlBinder.isGroupAggregate(type);
  }

  private StatusCode failStreamingStart(StatusCode status) {
    StatusCode cleanup = queries.retryFailedStartCleanup();
    boolean complete = cleanup.isOk();
    if (!complete) {
      status = cleanup;
    }
    status = streaming.failStart(status, complete);
    if (complete && !streaming.isActive()) {
      queries.completeFailedStart();
    }
    return status;
  }

  private StatusCode finishExplain(
      SqlScanCursor cursor, boolean analyzed, StatusCode executionStatus) {
    StatusCode cleanup = queries.closePhysicalScan(cursor);
    boolean physicalCleanupComplete = cleanup.isOk();
    StatusCode status = streaming.finish(
        cleanup, physicalCleanupComplete, queries.aggregateExecution());
    if (physicalCleanupComplete && !streaming.isActive()) {
      queries.completeScan(cursor);
    }
    if (status.isOk() && !executionStatus.isOk()) {
      status = executionStatus;
    }
    return status.isOk()
        ? queries.claimExplainResult(cursor, queries.aggregateExecution(), analyzed)
        : status;
  }

}
