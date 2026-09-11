package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.tx.api.IsolationLevel;

/** Owns the public session operation gate and delegates to concrete executors. */
final class SqlSessionExecutionCoordinator {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlBinder binder = new SqlBinder();
  private final SqlExpressionEvaluator expressions = new SqlExpressionEvaluator();
  private final SqlTemporalContext temporal = new SqlTemporalContext();
  private final SqlBlockPlanBinder blockBinder;
  private final SqlRowProjectionEvaluator rowExpressions;
  private final SqlViewExpander viewExpander = new SqlViewExpander(binder);
  private final SqlViewDefinitionValidator viewValidator =
      new SqlViewDefinitionValidator(binder);
  private final SqlTransactionState transactions;
  private final SqlCommandDispatcher dispatcher;
  private final SqlDmlExecutor dml;
  private final SqlPointCommandExecutor pointCommands;
  private final SqlStreamingStatementLifecycle streaming;
  private final SqlAtomicStatementLifecycle atomic;
  private final SqlQueryExecution queries;
  private final SqlStreamingQueryRouter streamingQueries;
  private final SqlSessionRuntimeLease runtimeLease = new SqlSessionRuntimeLease();
  private final SqlSessionShapeBudget shapeBudget;
  private final SqlSessionCloseLifecycle closes;
  private final SqlSessionStatementPreparation preparation;
  private final SqlSessionScanLifecycle scans;
  StatusCode awaitDurability() { return session.awaitDurability(); }

  SqlSessionExecutionCoordinator(
      RelationalDatabase database, RelationalSession session) {
    this(database, session, null, null);
  }

  SqlSessionExecutionCoordinator(
      RelationalDatabase database,
      RelationalSession session,
      SessionAuthorizer sessionAuthorizer) {
    this(database, session, sessionAuthorizer, null);
  }

  SqlSessionExecutionCoordinator(
      RelationalDatabase database,
      RelationalSession session,
      SessionAuthorizer sessionAuthorizer,
      io.riverdb.engine.runtime.SqlRuntimeLease lease) {
    this.session = session;
    shapeBudget = new SqlSessionShapeBudget(lease);
    blockBinder = new SqlBlockPlanBinder(temporal, binder, shapeBudget);
    rowExpressions = new SqlRowProjectionEvaluator(expressions, temporal, shapeBudget);
    bound = new BoundSqlStatement(shapeBudget);
    transactions = new SqlTransactionState(session, shapeBudget);
    dispatcher = new SqlCommandDispatcher(database, session, transactions, temporal);
    queries = new SqlQueryExecution(
        session,
        bound,
        expressions,
        temporal,
        rowExpressions,
        blockBinder,
        new SqlPhysicalPlan(shapeBudget),
        shapeBudget,
        binder);
    SqlStreamingQueryBinder streamingBindings = new SqlStreamingQueryBinder(
        session, bound, binder, queries, blockBinder, rowExpressions, temporal);
    streamingQueries = new SqlStreamingQueryRouter(
        session, bound, binder, viewExpander, queries, streamingBindings);
    dml = new SqlDmlExecutor(
        database,
        session,
        temporal,
        rowExpressions,
        queries.predicateEvaluator(),
        shapeBudget);
    pointCommands = new SqlPointCommandExecutor(
        session,
        bound,
        binder,
        viewExpander,
        dml,
        queries,
        blockBinder,
        rowExpressions,
        temporal,
        shapeBudget);
    streaming = new SqlStreamingStatementLifecycle(session, transactions);
    atomic = new SqlAtomicStatementLifecycle(session, transactions);
    preparation = new SqlSessionStatementPreparation(
        session, sessionAuthorizer, bound, binder, atomic);
    runtimeLease.claim(lease);
    closes = new SqlSessionCloseLifecycle(
        session, transactions, queries, streaming, temporal, shapeBudget, runtimeLease);
    scans = new SqlSessionScanLifecycle(
        session, preparation, queries, dispatcher, viewValidator, pointCommands,
        atomic, transactions, streaming, temporal, streamingQueries);
  }

  long retainedShapeBytes() { return shapeBudget.retainedBytes(); }
  long maximumShapeBytes() { return shapeBudget.maximumBytes(); }
  StatusCode reserveRetainedBytes(long bytes) { return shapeBudget.reserve(bytes); }
  StatusCode releaseRetainedBytes(long bytes) { return shapeBudget.release(bytes); }
  long preparedCompiles() { return preparation.preparedCompiles(); }
  long preparedExecutions() { return preparation.preparedExecutions(); }
  long preparedRecompiles() { return preparation.preparedRecompiles(); }

  boolean matchesCatalogGeneration(long expected) {
    return expected > 0 && session.matchesCatalogGeneration(expected);
  }

  StatusCode configureTransactionDiagnostics(
      long diagnosticTag, long diagnosticStepTag, long metricsEpoch) {
    if (closes.unavailable() || queries.hasActiveScan()) {
      return closes.unavailable() ? StatusCode.CLOSED : StatusCode.CONFLICT;
    }
    return session.configureTransactionDiagnostics(
        diagnosticTag, diagnosticStepTag, metricsEpoch);
  }

  StatusCode updateTransactionDiagnosticStep(long diagnosticStepTag) {
    if (closes.unavailable() || queries.hasActiveScan()) {
      return closes.unavailable() ? StatusCode.CLOSED : StatusCode.CONFLICT;
    }
    return session.updateTransactionDiagnosticStep(diagnosticStepTag);
  }

  void claimDatabaseLease(io.riverdb.engine.runtime.SqlRuntimeLease lease) {
    runtimeLease.claim(lease);
  }

  StatusCode execute(String sql, SqlExecutionResult result) {
    return execute(sql, null, result, false);
  }

  StatusCode execute(
      String sql, ParameterSet parameters, SqlExecutionResult result) {
    if (parameters == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return execute(sql, parameters, result, true);
  }

  StatusCode executePrepared(
      SqlPreparedPlan plan, ParameterSet parameters, SqlExecutionResult result) {
    if (plan == null || parameters == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (closes.unavailable()) return StatusCode.CLOSED;
    StatusCode status = retryPendingCleanup();
    if (!status.isOk()) return status;
    if (queries.hasActiveScan()) return StatusCode.CONFLICT;
    if (parameters.count() != plan.parameterCount()) {
      return StatusCode.PARAMETER_COUNT_MISMATCH;
    }
    if (transactions.isExplicit()) {
      result.setTransaction(true, session.visibleCommitSequence());
    }
    status = preparation.bindPrepared(plan, parameters, false);
    if (!status.isOk()) return status;
    status = executeBound(result);
    return status.isOk()
        ? preparation.publishPreparedBinding(plan, pointCommands.catalogGeneration()) : status;
  }

  StatusCode validatePrepared(
      String sql, SqlPreparedPlan candidate, SqlRetainedBudget budget,
      SqlPreparedValidationResult result) {
    if (budget == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = result.reset();
    if (!status.isOk()) return status;
    if (closes.unavailable()) return StatusCode.CLOSED;
    status = retryPendingCleanup();
    if (!status.isOk()) return status;
    if (queries.hasActiveScan()) return StatusCode.CONFLICT;
    return preparation.validatePrepared(sql, candidate, budget, result);
  }

  private StatusCode execute(
      String sql,
      ParameterSet parameters,
      SqlExecutionResult result,
      boolean typed) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (closes.unavailable()) {
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
    StatusCode status = preparation.parseCommand(sql, parameters, typed);
    if (!status.isOk()) {
      return status;
    }
    return executeBound(result);
  }

  private StatusCode executeBound(SqlExecutionResult result) {
    if (dispatcher.handles(bound.command.type())) {
      if (bound.command.type() == SqlCommandType.SCALAR_EXPRESSION) {
        return scans.executeScalar(result);
      }
      return dispatcher.execute(bound.command, viewValidator, atomic, result);
    }
    return SqlAtomicPointExecution.execute(
        atomic, temporal, pointCommands, transactions, session, streaming, result);
  }

  StatusCode beginScan(String sql, SqlScanCursor cursor) {
    return beginScan(sql, null, cursor, false);
  }

  StatusCode beginScan(
      String sql, ParameterSet parameters, SqlScanCursor cursor) {
    if (parameters == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return beginScan(sql, parameters, cursor, true);
  }

  StatusCode beginPreparedScan(
      SqlPreparedPlan plan, ParameterSet parameters, SqlScanCursor cursor) {
    StatusCode status = admitScan(cursor);
    return status.isOk() ? scans.beginPrepared(plan, parameters, cursor) : status;
  }

  StatusCode executePreparedSingleton(
      SqlPreparedPlan plan,
      ParameterSet parameters,
      SqlScanCursor cursor,
      SqlExecutionResult result,
      SqlPreparedQueryPath path) {
    if (path == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    path.reset();
    StatusCode status = admitScan(cursor);
    return status.isOk()
        ? scans.executePreparedSingleton(plan, parameters, cursor, result, path) : status;
  }

  private StatusCode beginScan(
      String sql,
      ParameterSet parameters,
      SqlScanCursor cursor,
      boolean typed) {
    StatusCode status = admitScan(cursor);
    return status.isOk() ? scans.begin(sql, parameters, cursor, typed) : status;
  }

  private StatusCode admitScan(SqlScanCursor cursor) {
    if (cursor == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (closes.unavailable()) return StatusCode.CLOSED;
    StatusCode status = retryPendingCleanup();
    if (!status.isOk()) {
      return status;
    }
    return queries.hasActiveScan() || cursor.isActive()
        ? StatusCode.CONFLICT : StatusCode.OK;
  }

  StatusCode nextScan(SqlScanCursor cursor, SqlScanRowResult result) {
    if (closes.unavailable()) {
      return StatusCode.CLOSED;
    }
    return scans.next(cursor, result);
  }

  CharSequence scanColumnName(SqlScanCursor cursor, int index) {
    if (closes.unavailable()) {
      return null;
    }
    return scans.columnName(cursor, index);
  }

  int scanColumnTypeDescriptor(SqlScanCursor cursor, int index) {
    if (closes.unavailable()) {
      return 0;
    }
    return scans.columnTypeDescriptor(cursor, index);
  }

  boolean scanColumnIsNullable(SqlScanCursor cursor, int index) {
    return !closes.unavailable() && scans.columnIsNullable(cursor, index);
  }

  StatusCode closeScan(SqlScanCursor cursor, SqlExecutionResult result) {
    if (closes.unavailable()) {
      return StatusCode.CLOSED;
    }
    return scans.close(cursor, result);
  }

  StatusCode beginProgram(
      io.riverdb.engine.api.IsolationLevel isolationLevel,
      SqlExecutionResult result) {
    if (isolationLevel == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (closes.unavailable()) return StatusCode.CLOSED;
    StatusCode status = retryPendingCleanup();
    if (!status.isOk()) return status;
    if (queries.hasActiveScan() || transactions.isExplicit()) return StatusCode.CONFLICT;
    status = transactions.beginProgram(transactionIsolation(isolationLevel));
    if (status.isOk()) result.setTransaction(true, session.visibleCommitSequence());
    return status;
  }

  private static IsolationLevel transactionIsolation(
      io.riverdb.engine.api.IsolationLevel isolationLevel) {
    return switch (isolationLevel) {
      case READ_COMMITTED -> IsolationLevel.READ_COMMITTED;
      case REPEATABLE_READ -> IsolationLevel.REPEATABLE_READ;
      case SERIALIZABLE -> IsolationLevel.SERIALIZABLE;
    };
  }

  StatusCode commitProgram(SqlExecutionResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (closes.unavailable()) return StatusCode.CLOSED;
    if (queries.hasActiveScan()) return StatusCode.CONFLICT;
    StatusCode status = transactions.commitExplicit();
    if (status.isOk()) result.setCommitSequence(transactions.commitSequence());
    return status;
  }

  StatusCode abortProgram(SqlExecutionResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (closes.unavailable()) return StatusCode.CLOSED;
    if (queries.hasActiveScan()) return StatusCode.CONFLICT;
    StatusCode status = transactions.abortExplicit();
    if (status.isOk()) result.setCommitSequence(transactions.commitSequence());
    return status;
  }

  boolean programTransactionActive() { return transactions.isProgram(); }

  StatusCode close() {
    if (closes.closed()) return StatusCode.CLOSED;
    if (!closes.closing()) {
      StatusCode status = retryPendingCleanup();
      if (!status.isOk()) return status;
    }
    return closes.close();
  }

  private StatusCode retryPendingCleanup() {
    StatusCode status = dispatcher.closeResources();
    if (status.isOk()) status = pointCommands.closeResources();
    if (status.isOk() && atomic.isActive()) {
      status = atomic.retry();
    }
    scans.finishAtomicTemporalIfIdle();
    if (status.isOk()) status = scans.retryFailedStartCleanup();
    return status;
  }

}
