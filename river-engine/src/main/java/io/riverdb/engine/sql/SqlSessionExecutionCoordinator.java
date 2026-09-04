package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlRuntimeParameterBindings;
import io.riverdb.sql.SqlStatementTemplate;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.tx.api.IsolationLevel;

/** Owns the public session operation gate and delegates to concrete executors. */
final class SqlSessionExecutionCoordinator {
  private final RelationalSession session;
  private final SessionAuthorizer authorizer;
  private final SqlParser parser = new SqlParser();
  private final SqlRuntimeParameterBindings runtimeParameters =
      new SqlRuntimeParameterBindings();
  private final SqlStatementTemplate.Result templateResult =
      new SqlStatementTemplate.Result();
  private final BoundSqlStatement bound;
  private final SqlBinder binder = new SqlBinder();
  private final SqlExpressionEvaluator expressions = new SqlExpressionEvaluator();
  private final SqlTemporalContext temporal = new SqlTemporalContext();
  private final SqlBlockPlanBinder blockBinder;
  private final SqlRowProjectionEvaluator rowExpressions;
  private final SqlViewExpander viewExpander = new SqlViewExpander(binder);
  private final SqlViewDefinitionValidator viewValidator =
      new SqlViewDefinitionValidator(binder);
  private final SqlBindingTableResolver bindingTables = new SqlBindingTableResolver();
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
  private long preparedCompiles;
  private long preparedExecutions;
  private long preparedRecompiles;

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
    authorizer = sessionAuthorizer;
    shapeBudget = new SqlSessionShapeBudget(lease);
    blockBinder = new SqlBlockPlanBinder(temporal, binder, shapeBudget);
    rowExpressions = new SqlRowProjectionEvaluator(expressions, temporal, shapeBudget);
    bound = new BoundSqlStatement(shapeBudget);
    transactions = new SqlTransactionState(session);
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
    runtimeLease.claim(lease);
    closes = new SqlSessionCloseLifecycle(
        session, transactions, queries, streaming, temporal, shapeBudget, runtimeLease);
  }

  long retainedShapeBytes() { return shapeBudget.retainedBytes(); }
  long maximumShapeBytes() { return shapeBudget.maximumBytes(); }
  StatusCode reserveRetainedBytes(long bytes) { return shapeBudget.reserve(bytes); }
  StatusCode releaseRetainedBytes(long bytes) { return shapeBudget.release(bytes); }
  long preparedCompiles() { return preparedCompiles; }
  long preparedExecutions() { return preparedExecutions; }
  long preparedRecompiles() { return preparedRecompiles; }

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
    boolean recompile = plan.needsRecompile(session);
    if (transactions.isExplicit()) {
      result.setTransaction(true, session.visibleCommitSequence());
    }
    bound.reset();
    status = plan.template().restore(bound.query, bound.command);
    if (status.isOk()) status = loadParameters(parameters, plan.parameterCount());
    if (status.isOk()) status = runtimeParameters.materialize(bound.query, bound.command);
    runtimeParameters.reset();
    if (status.isOk()) status = authorize(bound.command.type());
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    if (!status.isOk()) return status;
    preparedExecutions++;
    status = executeBound(result);
    return status.isOk()
        ? publishPreparedBinding(plan, recompile, pointCommands.catalogGeneration()) : status;
  }

  StatusCode validatePrepared(
      String sql, SqlRetainedBudget budget, SqlPreparedValidationResult result) {
    if (budget == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = result.reset();
    if (!status.isOk()) return status;
    if (closes.unavailable()) return StatusCode.CLOSED;
    StatusCode cleanup = retryPendingCleanup();
    if (!cleanup.isOk()) return cleanup;
    if (queries.hasActiveScan()) return StatusCode.CONFLICT;
    bound.reset();
    status = parser.parseTemplate(sql, bound.query, bound.command);
    if (status.isOk()) status = authorize(bound.command.type());
    boolean began = false;
    if (status.isOk()) {
      status = atomic.begin(IsolationLevel.READ_COMMITTED);
      began = status.isOk();
    }
    if (status.isOk() && bound.command.tableName().length() > 0) {
      status = bindingTables.resolve(session, bound.command.tableName(), bound.table);
    }
    long catalogGeneration = status.isOk() ? session.catalogGeneration() : 0;
    if (began) status = atomic.finish(status);
    long retainedBytes = status.isOk()
        ? SqlPreparedPlan.estimateByteCharge(bound.command, bound.query) : 0;
    if (status.isOk() && retainedBytes <= 0) status = StatusCode.FEATURE_NOT_SUPPORTED;
    boolean reserved = false;
    if (status.isOk()) {
      status = budget.reserveRetainedBytes(retainedBytes);
      reserved = status.isOk();
    }
    if (status.isOk()) status = SqlStatementTemplate.capture(
        bound.command, bound.query, parser.templateParameterCount(), templateResult);
    if (status.isOk()) status = result.complete(
        templateResult.value(), SqlSessionCommandKinds.query(bound.command.type()),
        catalogGeneration, budget, retainedBytes);
    if (status.isOk()) reserved = false;
    if (reserved) {
      StatusCode release = budget.releaseRetainedBytes(retainedBytes);
      if (!release.isOk()) status = release;
    }
    if (status.isOk()) preparedCompiles++;
    templateResult.reset();
    bound.reset();
    return status;
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
    bound.reset();
    StatusCode status = typed
        ? parseInvocation(sql, parameters)
        : SqlSessionCommandKinds.beginsSelect(sql)
            ? parser.parseQuery(sql, bound.query, bound.command)
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
    return executeBound(result);
  }

  private StatusCode executeBound(SqlExecutionResult result) {
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
    return beginScan(sql, parameters, cursor, true);
  }

  StatusCode beginPreparedScan(
      SqlPreparedPlan plan, ParameterSet parameters, SqlScanCursor cursor) {
    StatusCode status = admitScan(cursor);
    if (!status.isOk()) return status;
    if (plan == null || parameters == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (parameters.count() != plan.parameterCount()) {
      return StatusCode.PARAMETER_COUNT_MISMATCH;
    }
    boolean recompile = plan.needsRecompile(session);
    bound.reset();
    status = plan.template().restore(bound.query, bound.command);
    if (status.isOk()) status = loadParameters(parameters, plan.parameterCount());
    if (status.isOk()) status = runtimeParameters.materialize(bound.query, bound.command);
    runtimeParameters.reset();
    if (status.isOk()) status = authorize(bound.command.type());
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    if (status.isOk() && !SqlSessionCommandKinds.query(bound.command.type())) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk()) return status;
    long bindingCatalogGeneration = recompile ? session.catalogGeneration() : 0;
    preparedExecutions++;
    status = beginCompiledScan(cursor);
    return status.isOk()
        ? publishPreparedBinding(plan, recompile, bindingCatalogGeneration) : status;
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
    if (!status.isOk()) return status;
    if (plan == null || parameters == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (parameters.count() != plan.parameterCount()) {
      return StatusCode.PARAMETER_COUNT_MISMATCH;
    }
    boolean recompile = plan.needsRecompile(session);
    bound.reset();
    status = plan.template().restore(bound.query, bound.command);
    if (status.isOk()) status = loadParameters(parameters, plan.parameterCount());
    if (status.isOk()) status = runtimeParameters.materialize(bound.query, bound.command);
    runtimeParameters.reset();
    if (status.isOk()) status = authorize(bound.command.type());
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    if (status.isOk() && !SqlSessionCommandKinds.query(bound.command.type())) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk()) return status;
    SqlPhysicalStepKind physicalKind = SqlPhysicalStepClassifier.classifySingleton(bound);
    long bindingCatalogGeneration = recompile ? session.catalogGeneration() : 0;
    preparedExecutions++;
    if (physicalKind.point()) {
      path.point(true);
      status = executePoint(result);
      return status.isOk()
          ? publishPreparedBinding(plan, recompile, bindingCatalogGeneration) : status;
    }
    status = beginCompiledScan(cursor);
    return status.isOk()
        ? publishPreparedBinding(plan, recompile, bindingCatalogGeneration) : status;
  }

  private StatusCode beginScan(
      String sql,
      ParameterSet parameters,
      SqlScanCursor cursor,
      boolean typed) {
    StatusCode status = admitScan(cursor);
    if (status.isOk()) {
      status = parseScan(sql, parameters, typed);
    }
    if (!status.isOk()) {
      return status;
    }
    return beginCompiledScan(cursor);
  }

  private StatusCode beginCompiledScan(SqlScanCursor cursor) {
    boolean scalar = SqlBinder.isScalarAggregate(bound.command.type());
    boolean preexecuted = shouldPreexecuteScan();
    StatusCode status = StatusCode.OK;
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
    if (closes.unavailable()) {
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
      ParameterSet parameters,
      boolean typed) {
    bound.reset();
    StatusCode status = typed
        ? parseInvocation(sql, parameters)
        : parser.parseQuery(sql, bound.query, bound.command);
    if (status.isOk()) {
      status = authorize(bound.command.type());
    }
    if (status.isOk()) {
      status = binder.captureExecutableQuery(bound);
    }
    if (status.isOk() && !SqlSessionCommandKinds.query(bound.command.type())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status;
  }

  private StatusCode parseInvocation(String sql, ParameterSet parameters) {
    if (parameters == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = parser.parseTemplate(sql, bound.query, bound.command);
    if (status.isOk() && parser.templateParameterCount() != parameters.count()) {
      status = StatusCode.PARAMETER_COUNT_MISMATCH;
    }
    if (status.isOk()) status = loadParameters(parameters, parser.templateParameterCount());
    if (status.isOk()) status = runtimeParameters.materialize(bound.query, bound.command);
    runtimeParameters.reset();
    return status;
  }

  private StatusCode loadParameters(ParameterSet parameters, int expected) {
    if (parameters == null || parameters.count() != expected) {
      return StatusCode.PARAMETER_COUNT_MISMATCH;
    }
    StatusCode status = runtimeParameters.begin(expected, parameters.textBytes());
    for (int parameter = 0; status.isOk() && parameter < expected; parameter++) {
      int descriptor = parameters.typeDescriptorAt(parameter);
      int textLength = !parameters.isNull(parameter)
              && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
          ? parameters.textLengthAt(parameter) : 0;
      status = runtimeParameters.set(
          parameter, descriptor, parameters.decimalUnscaledHighAt(parameter),
          parameters.valueAt(parameter), parameters.isNull(parameter), textLength);
      for (int index = 0; status.isOk() && index < textLength; index++) {
        status = runtimeParameters.setTextByte(
            parameter, index, parameters.textByteAt(parameter, index));
      }
    }
    return status;
  }

  private StatusCode publishPreparedBinding(
      SqlPreparedPlan plan, boolean invalidated, long catalogGeneration) {
    if (!invalidated) return StatusCode.OK;
    if (catalogGeneration <= 0 || !session.matchesCatalogGeneration(catalogGeneration)) {
      return StatusCode.OK;
    }
    if (!plan.publishRecompile(catalogGeneration)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    preparedRecompiles++;
    return StatusCode.OK;
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
        && !queries.descriptorScanMatched()
        && !queries.explainOnly()) {
      queries.aggregateExecution().reset();
      status = queries.executePointQuery(queries.aggregateExecution());
    }
    if (!status.isOk()) {
      return failStreamingStart(status);
    }
    if (queries.explainOnly() && scalar && !queries.hasBlockPipelinePlan()
        && !queries.descriptorScanMatched()) {
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
    if (queries.explainOnly()
        && (queries.descriptorScanMatched() || queries.universalJoinMatched())) {
      StatusCode status = queries.claimPreparedPlan(cursor);
      if (status.isOk()) status = queries.describeCurrentPlan(cursor);
      return finishExplain(cursor, false, status);
    }
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
    if (closes.unavailable()) {
      return StatusCode.CLOSED;
    }
    return queries.nextScan(cursor, result);
  }

  CharSequence scanColumnName(SqlScanCursor cursor, int index) {
    if (closes.unavailable()) {
      return null;
    }
    return queries.scanColumnName(cursor, index);
  }

  int scanColumnTypeDescriptor(SqlScanCursor cursor, int index) {
    if (closes.unavailable()) {
      return 0;
    }
    return queries.scanColumnTypeDescriptor(cursor, index);
  }

  boolean scanColumnIsNullable(SqlScanCursor cursor, int index) {
    return !closes.unavailable() && queries.scanColumnIsNullable(cursor, index);
  }

  StatusCode closeScan(SqlScanCursor cursor, SqlExecutionResult result) {
    if (closes.unavailable()) {
      return StatusCode.CLOSED;
    }
    if (cursor == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (queries.syntheticScan()) {
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
    result.reset();
    StatusCode terminal = queries.terminalStatus();
    StatusCode cleanup = queries.closePhysicalScan(cursor);
    boolean physicalCleanupComplete = cleanup.isOk();
    StatusCode status = terminal != null && physicalCleanupComplete
        ? streaming.finishDelivered(terminal, true, result)
        : streaming.finish(cleanup, physicalCleanupComplete, result);
    if (physicalCleanupComplete && !streaming.isActive()) {
      queries.completeScan(cursor);
      temporal.finishStatement();
    }
    return status;
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
    status = transactions.beginExplicit(transactionIsolation(isolationLevel));
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

  boolean programTransactionActive() { return transactions.isExplicit(); }

  StatusCode close() {
    if (closes.closed()) return StatusCode.CLOSED;
    if (!closes.closing()) {
      StatusCode status = retryPendingCleanup();
      if (!status.isOk()) return status;
    }
    return closes.close();
  }

  private StatusCode executePoint(SqlExecutionResult result) {
    return SqlAtomicPointExecution.execute(
        atomic, temporal, pointCommands, transactions, session, streaming, result);
  }

  private StatusCode retryPendingCleanup() {
    StatusCode status = dispatcher.closeResources();
    if (status.isOk()) status = pointCommands.closeResources();
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
    return streamingQueries.prepare();
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
    boolean physicalCleanupComplete = cleanup.isOk();
    StatusCode status = streaming.finish(
        executionStatus.isOk() ? cleanup : executionStatus,
        physicalCleanupComplete,
        queries.aggregateExecution());
    if (physicalCleanupComplete && !streaming.isActive()) {
      queries.completeScan(cursor);
      temporal.finishStatement();
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
