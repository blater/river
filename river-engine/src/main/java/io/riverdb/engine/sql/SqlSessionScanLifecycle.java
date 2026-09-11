package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.tx.api.IsolationLevel;

/** Owns reusable scan startup, row delivery, and completion. */
final class SqlSessionScanLifecycle {
  private final RelationalSession session;
  private final SqlSessionStatementPreparation preparation;
  private final BoundSqlStatement bound;
  private final SqlQueryExecution queries;
  private final SqlCommandDispatcher dispatcher;
  private final SqlViewDefinitionValidator viewValidator;
  private final SqlPointCommandExecutor pointCommands;
  private final SqlAtomicStatementLifecycle atomic;
  private final SqlTransactionState transactions;
  private final SqlStreamingStatementLifecycle streaming;
  private final SqlTemporalContext temporal;
  private final SqlStreamingQueryRouter streamingQueries;

  SqlSessionScanLifecycle(
      RelationalSession relationalSession,
      SqlSessionStatementPreparation statementPreparation,
      SqlQueryExecution queryExecution,
      SqlCommandDispatcher commandDispatcher,
      SqlViewDefinitionValidator validator,
      SqlPointCommandExecutor pointExecutor,
      SqlAtomicStatementLifecycle atomicLifecycle,
      SqlTransactionState transactionState,
      SqlStreamingStatementLifecycle streamingLifecycle,
      SqlTemporalContext temporalContext,
      SqlStreamingQueryRouter streamingQueryRouter) {
    session = relationalSession;
    preparation = statementPreparation;
    bound = statementPreparation.bound();
    queries = queryExecution;
    dispatcher = commandDispatcher;
    viewValidator = validator;
    pointCommands = pointExecutor;
    atomic = atomicLifecycle;
    transactions = transactionState;
    streaming = streamingLifecycle;
    temporal = temporalContext;
    streamingQueries = streamingQueryRouter;
  }

  StatusCode begin(String sql, ParameterSet parameters, SqlScanCursor cursor, boolean typed) {
    StatusCode status = preparation.parseScan(sql, parameters, typed);
    return status.isOk() ? beginCompiled(cursor) : status;
  }

  StatusCode beginPrepared(
      SqlPreparedPlan plan, ParameterSet parameters, SqlScanCursor cursor) {
    StatusCode status = preparation.bindPrepared(plan, parameters, true);
    return status.isOk() ? beginCompiledAndPublish(plan, cursor) : status;
  }

  StatusCode executePreparedSingleton(
      SqlPreparedPlan plan,
      ParameterSet parameters,
      SqlScanCursor cursor,
      SqlExecutionResult result,
      SqlPreparedQueryPath path) {
    StatusCode status = preparation.bindPrepared(plan, parameters, true);
    if (!status.isOk()) return status;
    SqlPhysicalStepKind physicalKind = SqlPhysicalStepClassifier.classifySingleton(bound);
    if (physicalKind.point()) {
      path.point(true);
      status = executePoint(result);
      return status.isOk()
          ? preparation.publishPreparedBinding(plan) : status;
    }
    return beginCompiledAndPublish(plan, cursor);
  }

  StatusCode next(SqlScanCursor cursor, SqlScanRowResult result) {
    return queries.nextScan(cursor, result);
  }

  CharSequence columnName(SqlScanCursor cursor, int index) {
    return queries.scanColumnName(cursor, index);
  }

  int columnTypeDescriptor(SqlScanCursor cursor, int index) {
    return queries.scanColumnTypeDescriptor(cursor, index);
  }

  boolean columnIsNullable(SqlScanCursor cursor, int index) {
    return queries.scanColumnIsNullable(cursor, index);
  }

  StatusCode close(SqlScanCursor cursor, SqlExecutionResult result) {
    if (cursor == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (queries.syntheticScan()) return closeSynthetic(cursor, result);
    result.reset();
    StatusCode terminal = queries.terminalStatus();
    StatusCode cleanup = queries.closePhysicalScan(cursor);
    boolean complete = cleanup.isOk();
    StatusCode status = terminal != null && complete
        ? streaming.finishDelivered(terminal, true, result)
        : streaming.finish(cleanup, complete, result);
    if (complete && !streaming.isActive()) {
      queries.completeScan(cursor);
      temporal.finishStatement();
    }
    return status;
  }

  StatusCode executeScalar(SqlExecutionResult result) {
    StatusCode status = atomic.begin(IsolationLevel.READ_COMMITTED);
    boolean began = status.isOk();
    if (status.isOk()) status = temporal.beginStatement();
    if (status.isOk()) status = temporal.resolveScalar(bound.command);
    if (status.isOk()) status = dispatcher.execute(bound.command, viewValidator, atomic, result);
    if (began) status = atomic.finish(status);
    finishAtomicTemporalIfIdle();
    return status;
  }

  private StatusCode executePoint(SqlExecutionResult result) {
    return SqlAtomicPointExecution.execute(
        atomic, temporal, pointCommands, transactions, session, streaming, result);
  }

  void finishAtomicTemporalIfIdle() {
    if (!atomic.isActive() && !streaming.isActive() && temporal.statementActive()) {
      temporal.finishStatement();
    }
  }

  StatusCode retryFailedStartCleanup() {
    if (!streaming.isActive() || queries.hasActiveScan()) return StatusCode.OK;
    StatusCode status = queries.retryFailedStartCleanup();
    boolean complete = status.isOk();
    status = streaming.failStart(status, complete);
    if (complete && !streaming.isActive()) {
      queries.completeFailedStart();
      temporal.finishStatement();
    }
    return status;
  }

  private StatusCode beginCompiledAndPublish(SqlPreparedPlan plan, SqlScanCursor cursor) {
    StatusCode status = beginCompiled(cursor);
    return status.isOk()
        ? preparation.publishPreparedBinding(plan) : status;
  }

  private StatusCode beginCompiled(SqlScanCursor cursor) {
    boolean scalar = SqlBinder.isScalarAggregate(bound.command.type());
    boolean preexecuted = shouldPreexecute();
    StatusCode status = preexecuted ? preexecute() : StatusCode.OK;
    if (!status.isOk()) return status;
    status = queries.initializeScan();
    if (!status.isOk()) return status;
    return preexecuted ? beginPreexecuted(cursor) : beginStreaming(cursor, scalar);
  }

  private boolean shouldPreexecute() {
    boolean explainOnly = bound.query.isExplain() && !bound.query.isAnalyze();
    SqlCommandType type = bound.command.type();
    return !explainOnly && type == SqlCommandType.SCALAR_EXPRESSION
        || !bound.query.isExplain() && type == SqlCommandType.NEXT_SEQUENCE_VALUE;
  }

  private StatusCode preexecute() {
    queries.aggregateExecution().reset();
    if (bound.command.type() == SqlCommandType.NEXT_SEQUENCE_VALUE) {
      return dispatcher.execute(
          bound.command, viewValidator, atomic, queries.aggregateExecution());
    }
    return bound.command.type() == SqlCommandType.SCALAR_EXPRESSION
        ? executeScalar(queries.aggregateExecution())
        : executePoint(queries.aggregateExecution());
  }

  private StatusCode beginPreexecuted(SqlScanCursor cursor) {
    StatusCode status = queries.beginScan(cursor);
    if (!status.isOk() || !bound.query.isAnalyze()) return status;
    status = queries.describeCurrentPlan(cursor);
    if (status.isOk()) status = queries.drainAnalyze(cursor);
    if (status.isOk()) {
      queries.completeScan(cursor);
      return queries.claimExplainResult(cursor, queries.aggregateExecution(), true);
    }
    return status;
  }

  private StatusCode beginStreaming(SqlScanCursor cursor, boolean scalar) {
    StatusCode status = streaming.begin();
    if (status.isOk()) status = temporal.beginStatement();
    if (status.isOk()) status = streamingQueries.prepare();
    if (status.isOk()) queries.adoptPreparedQuery();
    if (status.isOk() && scalar && !queries.hasBlockPipelinePlan()
        && !queries.descriptorScanMatched() && !queries.explainOnly()) {
      queries.aggregateExecution().reset();
      status = queries.executePointQuery(queries.aggregateExecution());
    }
    if (!status.isOk()) return failStreamingStart(status);
    if (queries.explainOnly() && scalar && !queries.hasBlockPipelinePlan()
        && !queries.descriptorScanMatched()) return explainScalar(cursor);
    return beginPreparedStreaming(cursor);
  }

  private StatusCode explainScalar(SqlScanCursor cursor) {
    queries.aggregateExecution().reset();
    StatusCode status = queries.configureScalarAggregateExplain();
    status = streaming.finish(status, queries.aggregateExecution());
    if (!streaming.isActive()) temporal.finishStatement();
    return status.isOk()
        ? queries.claimExplainResult(cursor, queries.aggregateExecution(), false) : status;
  }

  private StatusCode beginPreparedStreaming(SqlScanCursor cursor) {
    if (queries.explainOnly()
        && (queries.descriptorScanMatched() || queries.universalJoinMatched())) {
      StatusCode status = queries.claimPreparedPlan(cursor);
      if (status.isOk()) status = queries.describeCurrentPlan(cursor);
      return finishExplain(cursor, false, status);
    }
    StatusCode status = queries.beginScan(cursor);
    if (!status.isOk()) return failStreamingStart(status);
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

  private StatusCode closeSynthetic(SqlScanCursor cursor, SqlExecutionResult result) {
    StatusCode terminal = queries.terminalStatus();
    StatusCode status = queries.closeSyntheticScan(cursor, result);
    if (!streaming.isActive()) return status;
    boolean complete = status.isOk();
    status = terminal != null && complete
        ? streaming.finishDelivered(terminal, true, result)
        : streaming.finish(status, complete, result);
    if (complete && !streaming.isActive()) temporal.finishStatement();
    return status;
  }

  private StatusCode failStreamingStart(StatusCode status) {
    StatusCode cleanup = queries.retryFailedStartCleanup();
    boolean complete = cleanup.isOk();
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
    boolean complete = cleanup.isOk();
    StatusCode status = streaming.finish(
        executionStatus.isOk() ? cleanup : executionStatus,
        complete, queries.aggregateExecution());
    if (complete && !streaming.isActive()) {
      queries.completeScan(cursor);
      temporal.finishStatement();
    }
    return status.isOk()
        ? queries.claimExplainResult(cursor, queries.aggregateExecution(), analyzed) : status;
  }

}
