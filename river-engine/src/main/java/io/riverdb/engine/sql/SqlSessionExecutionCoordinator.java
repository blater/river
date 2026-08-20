package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlParser;
import io.riverdb.tx.api.IsolationLevel;

/** Owns the public session operation gate and delegates to concrete executors. */
final class SqlSessionExecutionCoordinator {
  private final RelationalSession session;
  private final SessionAuthorizer authorizer;
  private final SqlParser parser = new SqlParser();
  private final SqlParameterSetSource parameterSource = new SqlParameterSetSource();
  private final BoundSqlStatement bound = new BoundSqlStatement();
  private final SqlBinder binder = new SqlBinder();
  private final SqlExpressionEvaluator expressions = new SqlExpressionEvaluator();
  private final SqlTemporalContext temporal = new SqlTemporalContext();
  private final SqlBlockPlanBinder blockBinder =
      new SqlBlockPlanBinder(temporal, binder);
  private final SqlRowProjectionEvaluator rowExpressions =
      new SqlRowProjectionEvaluator(expressions, temporal);
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
    this(database, session, null);
  }

  SqlSessionExecutionCoordinator(
      RelationalDatabase database,
      RelationalSession session,
      SessionAuthorizer sessionAuthorizer) {
    this.session = session;
    authorizer = sessionAuthorizer;
    transactions = new SqlTransactionState(session);
    dispatcher = new SqlCommandDispatcher(database, session, transactions, temporal);
    queries = new SqlQueryExecution(
        session,
        bound,
        expressions,
        temporal,
        rowExpressions,
        blockBinder);
    dml = new SqlDmlExecutor(
        database,
        session,
        temporal,
        rowExpressions,
        queries.predicateEvaluator());
    pointCommands = new SqlPointCommandExecutor(
        session,
        bound,
        binder,
        viewExpander,
        dml,
        queries,
        blockBinder,
        rowExpressions);
    streaming = new SqlStreamingStatementLifecycle(session, transactions);
    atomic = new SqlAtomicStatementLifecycle(session, transactions);
  }

  StatusCode execute(String sql, SqlExecutionResult result) {
    return execute(sql, null, result, false);
  }

  StatusCode execute(
      String sql, ParameterSet parameters, SqlExecutionResult result) {
    if (parameters == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    parameterSource.set(parameters);
    try {
      return execute(sql, parameterSource, result, true);
    } finally {
      parameterSource.reset();
    }
  }

  private StatusCode execute(
      String sql,
      io.riverdb.sql.SqlParameterSource parameters,
      SqlExecutionResult result,
      boolean typed) {
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
    StatusCode status = beginsSelect(sql)
        ? typed
            ? parser.parseQuery(
                sql, parameters, bound.query, bound.command)
            : parser.parseQuery(sql, bound.query, bound.command)
        : typed
            ? parser.parse(sql, parameters, bound.command)
            : parser.parse(sql, bound.command);
    if (status.isOk()) {
      status = authorize(bound.command.type());
    }
    if (status.isOk()) {
      status = binder.captureExecutableQuery(bound);
    }
    if (!status.isOk()) {
      return status;
    }
    if (dispatcher.handles(bound.command.type())) {
      if (bound.command.type() == SqlCommandType.SCALAR_EXPRESSION) {
        return executeScalar(result);
      }
      return dispatcher.execute(bound.command, viewValidator, atomic, result);
    }
    return executePoint(result);
  }

  StatusCode beginScan(String sql, SqlScanCursor cursor) {
    return beginScan(sql, null, cursor, false);
  }

  StatusCode beginScan(
      String sql, ParameterSet parameters, SqlScanCursor cursor) {
    if (parameters == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    parameterSource.set(parameters);
    try {
      return beginScan(sql, parameterSource, cursor, true);
    } finally {
      parameterSource.reset();
    }
  }

  private StatusCode beginScan(
      String sql,
      io.riverdb.sql.SqlParameterSource parameters,
      SqlScanCursor cursor,
      boolean typed) {
    StatusCode status = admitScan(cursor);
    if (status.isOk()) {
      status = parseScan(sql, parameters, typed);
    }
    if (!status.isOk()) {
      return status;
    }
    boolean scalar = SqlBinder.isScalarAggregate(bound.command.type());
    boolean preexecuted = shouldPreexecuteScan();
    if (preexecuted) {
      status = preexecuteScan();
    }
    if (!status.isOk()) {
      return status;
    }
    status = queries.initializeScan();
    if (!status.isOk()) {
      return status;
    }
    if (preexecuted) {
      return beginPreexecutedScan(cursor);
    }
    return beginStreamingScan(cursor, scalar);
  }

  private StatusCode admitScan(SqlScanCursor cursor) {
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (closed) {
      return StatusCode.CLOSED;
    }
    StatusCode status = retryPendingCleanup();
    if (!status.isOk()) {
      return status;
    }
    return queries.hasActiveScan() || cursor.isActive()
        ? StatusCode.CONFLICT : StatusCode.OK;
  }

  private StatusCode parseScan(
      String sql,
      io.riverdb.sql.SqlParameterSource parameters,
      boolean typed) {
    bound.reset();
    StatusCode status = typed
        ? parser.parseQuery(sql, parameters, bound.query, bound.command)
        : parser.parseQuery(sql, bound.query, bound.command);
    if (status.isOk()) {
      status = authorize(bound.command.type());
    }
    if (status.isOk()) {
      status = binder.captureExecutableQuery(bound);
    }
    if (status.isOk() && !isQueryCommand(bound.command.type())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status;
  }

  private boolean shouldPreexecuteScan() {
    boolean explainOnly = bound.query.isExplain() && !bound.query.isAnalyze();
    SqlCommandType type = bound.command.type();
    boolean expression = type == SqlCommandType.SCALAR_EXPRESSION;
    boolean sequence = type == SqlCommandType.NEXT_SEQUENCE_VALUE;
    return !explainOnly && expression
        || !bound.query.isExplain() && sequence;
  }

  private StatusCode preexecuteScan() {
    queries.aggregateExecution().reset();
    SqlCommandType type = bound.command.type();
    if (type == SqlCommandType.NEXT_SEQUENCE_VALUE) {
      return dispatcher.execute(
          bound.command, viewValidator, atomic, queries.aggregateExecution());
    }
    if (type == SqlCommandType.SCALAR_EXPRESSION) {
      return executeScalar(queries.aggregateExecution());
    }
    return executePoint(queries.aggregateExecution());
  }

  private StatusCode beginPreexecutedScan(SqlScanCursor cursor) {
    StatusCode status = queries.beginScan(cursor);
    if (!status.isOk() || !bound.query.isAnalyze()) {
      return status;
    }
    status = queries.describeCurrentPlan(cursor);
    if (status.isOk()) status = queries.drainAnalyze(cursor);
    if (status.isOk()) {
      queries.completeScan(cursor);
      return queries.claimExplainResult(cursor, queries.aggregateExecution(), true);
    }
    return status;
  }

  private StatusCode beginStreamingScan(SqlScanCursor cursor, boolean scalar) {
    StatusCode status = streaming.begin();
    if (!status.isOk()) {
      return status;
    }
    status = temporal.beginStatement();
    if (status.isOk()) {
      status = prepareStreamingQuery();
    }
    if (status.isOk()) {
      adoptStreamingQuery();
    }
    if (status.isOk() && scalar && !queries.hasBlockPipelinePlan()
        && !queries.explainOnly()) {
      queries.aggregateExecution().reset();
      status = queries.executePointQuery(queries.aggregateExecution());
    }
    if (!status.isOk()) {
      return failStreamingStart(status);
    }
    if (queries.explainOnly() && scalar && !queries.hasBlockPipelinePlan()) {
      return explainScalarScan(cursor);
    }
    return beginPreparedStreamingScan(cursor);
  }

  private void adoptStreamingQuery() {
    queries.adoptPreparedQuery();
  }

  private StatusCode explainScalarScan(SqlScanCursor cursor) {
    queries.aggregateExecution().reset();
    StatusCode status = queries.configureScalarAggregateExplain();
    status = streaming.finish(status, queries.aggregateExecution());
    if (!streaming.isActive()) {
      temporal.finishStatement();
    }
    return status.isOk()
        ? queries.claimExplainResult(cursor, queries.aggregateExecution(), false)
        : status;
  }

  private StatusCode beginPreparedStreamingScan(SqlScanCursor cursor) {
    StatusCode status = queries.beginScan(cursor);
    if (!status.isOk()) {
      return failStreamingStart(status);
    }
    if (queries.explainOnly()) {
      status = queries.describeCurrentPlan(cursor);
      return finishExplain(cursor, false, status);
    }
    if (bound.query.isAnalyze()) {
      status = queries.describeCurrentPlan(cursor);
      if (status.isOk()) status = queries.drainAnalyze(cursor);
      return finishExplain(cursor, true, status);
    }
    return StatusCode.OK;
  }

  private StatusCode authorize(SqlCommandType type) {
    if (authorizer == null) {
      return StatusCode.OK;
    }
    return authorizer.authorize(
        SqlCommandAuthorization.requiredPermission(type));
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

  boolean scanColumnIsNullable(SqlScanCursor cursor, int index) {
    return !closed && queries.scanColumnIsNullable(cursor, index);
  }

  StatusCode closeScan(SqlScanCursor cursor, SqlExecutionResult result) {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (cursor == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (queries.syntheticScan()) {
      StatusCode status = queries.closeSyntheticScan(cursor, result);
      if (!streaming.isActive()) return status;
      boolean complete = status.isOk();
      status = streaming.finish(status, complete, result);
      if (complete && !streaming.isActive()) temporal.finishStatement();
      return status;
    }
    result.reset();
    StatusCode status = queries.closePhysicalScan(cursor);
    boolean physicalCleanupComplete = status.isOk();
    status = streaming.finish(status, physicalCleanupComplete, result);
    if (physicalCleanupComplete && !streaming.isActive()) {
      queries.completeScan(cursor);
      temporal.finishStatement();
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
      status = temporal.beginStatement();
    }
    if (status.isOk()) {
      status = pointCommands.execute(result);
    }
    boolean select = pointCommands.isPointQuery();
    if (began) {
      status = atomic.finish(status);
    }
    finishAtomicTemporalIfIdle();
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
    finishAtomicTemporalIfIdle();
    if (status.isOk() && streaming.isActive() && !queries.hasActiveScan()) {
      status = queries.retryFailedStartCleanup();
      boolean complete = status.isOk();
      status = streaming.failStart(status, complete);
      if (complete && !streaming.isActive()) {
        queries.completeFailedStart();
        temporal.finishStatement();
      }
    }
    return status;
  }

  private StatusCode prepareStreamingQuery() {
    SqlCommandType type = bound.command.type();
    if (type == SqlCommandType.SHOW_TABLES
        || type == SqlCommandType.SHOW_INDEXES
        || type == SqlCommandType.SHOW_COLUMNS) {
      return binder.captureExecutableQuery(bound);
    }
    StatusCode expansion = viewExpander.resolve(session, bound, binder);
    if (expansion.isOk()) expansion = binder.captureExecutableQuery(bound);
    if (!expansion.isOk()) return expansion;
    if (bound.query.isBlockPipeline()) return prepareBlockPipeline();
    if (type == SqlCommandType.JOIN_SCAN) {
      return prepareJoinQuery();
    }
    if (SqlBinder.isGroupAggregate(type)) {
      return prepareGroupQuery();
    }
    if (type == SqlCommandType.DISTINCT_SCAN) {
      return prepareDistinctQuery();
    }
    return prepareDataQuery(type);
  }

  private StatusCode prepareBlockPipeline() {
    StatusCode status = bound.query.isBlockPipeline()
        ? StatusCode.OK : StatusCode.CORRUPTION;
    if (status.isOk() && !bound.query.isBlockPipeline()) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) status = blockBinder.bind(session, bound, rowExpressions);
    if (status.isOk()) status = queries.prepareBlockPipeline();
    return publishPreparedQuery(status);
  }

  private StatusCode prepareJoinQuery() {
    StatusCode status = session.resolveTable(bound.command.joinTableName(), bound.joinTable);
    if (status.isOk()) {
      status = binder.bindQueryBlocks(session, bound);
    }
    if (status.isOk()) {
      status = binder.bindJoin(bound.command, bound);
    }
    if (status.isOk()) {
      status = queries.prepareProjectionPrograms();
    }
    return publishPreparedQuery(status);
  }

  private StatusCode prepareGroupQuery() {
    StatusCode status = resolveRootTableAndBlocks();
    if (status.isOk()) {
      status = binder.bindGroupAggregate(bound.command, bound.query, bound);
    }
    if (status.isOk()) {
      status = queries.prepareProjectionPrograms();
    }
    return publishPreparedQuery(status);
  }

  private StatusCode prepareDistinctQuery() {
    StatusCode status = resolveRootTableAndBlocks();
    if (status.isOk()) {
      status = binder.bindDistinct(bound.command, bound.query, bound);
    }
    if (status.isOk()) {
      status = queries.prepareProjectionPrograms();
    }
    return publishPreparedQuery(status);
  }

  private StatusCode resolveRootTableAndBlocks() {
    return binder.bindQueryBlocks(session, bound);
  }

  private StatusCode prepareDataQuery(SqlCommandType type) {
    StatusCode status = resolveRootTableAndBlocks();
    if (status.isOk()) {
      status = binder.bindDataCommand(bound.command, bound.query, bound);
    }
    if (status.isOk()) {
      status = queries.prepareNested();
    }
    if (status.isOk() && bound.command.isOrdered()) {
      status = binder.bindOrder(bound.command, bound);
    }
    if (status.isOk()) {
      status = queries.prepareProjectionPrograms();
    }
    return publishPreparedQuery(status);
  }

  private StatusCode publishPreparedQuery(StatusCode status) {
    if (status.isOk()) {
      binder.publishExecutableQuery(bound);
    }
    return status;
  }

  private static boolean isQueryCommand(SqlCommandType type) {
    return type == SqlCommandType.SHOW_TABLES
        || type == SqlCommandType.SHOW_INDEXES
        || type == SqlCommandType.SHOW_COLUMNS
        || type == SqlCommandType.SCAN
        || type == SqlCommandType.SELECT
        || type == SqlCommandType.DISTINCT_SCAN
        || type == SqlCommandType.JOIN_SCAN
        || type == SqlCommandType.NEXT_SEQUENCE_VALUE
        || type == SqlCommandType.SCALAR_EXPRESSION
        || SqlBinder.isScalarAggregate(type)
        || SqlBinder.isGroupAggregate(type);
  }

  private static boolean beginsSelect(String sql) {
    if (sql == null) return false;
    int index = 0;
    while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
      index++;
    }
    String keyword = "SELECT";
    if (sql.length() - index < keyword.length()) return false;
    for (int offset = 0; offset < keyword.length(); offset++) {
      if (Character.toUpperCase(sql.charAt(index + offset)) != keyword.charAt(offset)) {
        return false;
      }
    }
    int end = index + keyword.length();
    return end == sql.length()
        || !Character.isLetterOrDigit(sql.charAt(end)) && sql.charAt(end) != '_';
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
      temporal.finishStatement();
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
      temporal.finishStatement();
    }
    if (status.isOk() && !executionStatus.isOk()) {
      status = executionStatus;
    }
    return status.isOk()
        ? queries.claimExplainResult(cursor, queries.aggregateExecution(), analyzed)
        : status;
  }

  private StatusCode executeScalar(SqlExecutionResult result) {
    StatusCode status = atomic.begin(IsolationLevel.READ_COMMITTED);
    boolean began = status.isOk();
    if (status.isOk()) {
      status = temporal.beginStatement();
    }
    if (status.isOk()) {
      status = temporal.resolveScalar(bound.command);
    }
    if (status.isOk()) {
      status = dispatcher.execute(bound.command, viewValidator, atomic, result);
    }
    if (began) {
      status = atomic.finish(status);
    }
    finishAtomicTemporalIfIdle();
    return status;
  }

  private void finishAtomicTemporalIfIdle() {
    if (!atomic.isActive() && !streaming.isActive() && temporal.statementActive()) {
      temporal.finishStatement();
    }
  }

}
