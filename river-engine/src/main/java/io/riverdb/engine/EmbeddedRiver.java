package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.QueryMetadata;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.engine.sql.SqlExecutionResult;
import io.riverdb.engine.sql.SqlPreparedPlan;
import io.riverdb.engine.sql.SqlPublicResultPublisher;
import io.riverdb.engine.sql.SqlScanCursor;
import io.riverdb.engine.sql.SqlScanRowResult;
import io.riverdb.engine.sql.SqlSession;
import io.riverdb.engine.sql.SqlSessionOpenResult;
import java.nio.file.Path;

/** Entry point for the dependency-clean embedded River API. */
public final class EmbeddedRiver {
  private EmbeddedRiver() {
  }

  /** Returns the runtime tzdb version used by session zone conversion. */
  public static String timeZoneDatabaseVersion() {
    return SqlSession.timeZoneDatabaseVersion();
  }

  /** Appends bounded embedded lock diagnostics for the managed-server boundary. */
  public static StatusCode appendDeadlockDiagnostics(
      RiverDatabase database, StringBuilder target) {
    if (!(database instanceof EngineDatabase engine) || target == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return engine.database.appendDeadlockDiagnostics(target);
  }

  /** Appends internal commit/WAL diagnostics without exposing table types through engine-api. */
  public static StatusCode appendCommitDiagnostics(
      RiverDatabase database, StringBuilder target) {
    if (!(database instanceof EngineDatabase engine) || target == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return engine.database.appendCommitDiagnostics(target);
  }

  /** Starts one aggregate-only capture after the caller establishes quiescence. */
  public static StatusCode beginPerformanceCapture(RiverDatabase database) {
    if (!(database instanceof EngineDatabase engine)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return engine.database.beginPerformanceCapture();
  }

  /** Ends and formats the active capture after all admitted work drains. */
  public static StatusCode endPerformanceCapture(
      RiverDatabase database, StringBuilder target) {
    if (!(database instanceof EngineDatabase engine) || target == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return engine.database.endPerformanceCapture(target);
  }

  public static StatusCode cancelPerformanceCapture(RiverDatabase database) {
    if (!(database instanceof EngineDatabase engine)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return engine.database.cancelPerformanceCapture();
  }

  public static StatusCode create(
      DatabaseResourcePlanRequest resourceRequest,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      DatabaseOpenResult result) {
    return create(
        resourceRequest, directory, database, generation, maximumActiveTransactions,
        EmbeddedLockDiagnosticsConfig.disabled(), result);
  }

  public static StatusCode create(
      DatabaseResourcePlanRequest resourceRequest,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      DatabaseOpenResult result) {
    return open(
        resourceRequest,
        directory,
        database,
        generation,
        maximumActiveTransactions,
        true,
        lockDiagnostics,
        result);
  }

  public static StatusCode openExisting(
      DatabaseResourcePlanRequest resourceRequest,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      DatabaseOpenResult result) {
    return open(
        resourceRequest,
        directory,
        database,
        generation,
        maximumActiveTransactions,
        false,
        EmbeddedLockDiagnosticsConfig.disabled(),
        result);
  }

  private static StatusCode open(
      DatabaseResourcePlanRequest resourceRequest,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      boolean create,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      DatabaseOpenResult result) {
    if (resourceRequest == null || lockDiagnostics == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    StatusCode status = create
        ? RelationalDatabase.create(
            resourceRequest, directory, database, generation, maximumActiveTransactions,
            lockDiagnostics, opened)
        : RelationalDatabase.openExisting(
            resourceRequest, directory, database, generation,
            maximumActiveTransactions, opened);
    if (!status.isOk()) {
      result.detail().copyFrom(opened.detail());
      if (result.detail().code().isOk()) result.detail().set(status);
      return status;
    }
    EngineDatabase engine;
    try {
      engine = new EngineDatabase(opened.database());
    } catch (OutOfMemoryError failure) {
      StatusCode cleanup = opened.database().close();
      status = cleanup.isOk() ? StatusCode.RESOURCE_EXHAUSTED : cleanup;
      result.detail().set(status);
      return status;
    }
    status = result.complete(engine);
    if (!status.isOk()) {
      engine.close();
      result.detail().set(status);
    }
    return status;
  }

  private static final class EngineDatabase implements RiverDatabase {
    private final RelationalDatabase database;
    private final DeferredSessionCleanup terminalCleanup;
    private int openSessions;
    private boolean closed;

    private EngineDatabase(RelationalDatabase relational) {
      database = relational;
      terminalCleanup = new DeferredSessionCleanup(this);
    }

    @Override
    public int activeTransactionCount() { return database.activeTransactionCount(); }

    @Override
    public int retainedSnapshotCount() { return database.retainedSnapshotCount(); }

    @Override
    public long activeLockCount() { return database.activeLockCount(); }

    @Override
    public long waitingLockCount() { return database.waitingLockCount(); }

    @Override
    public long lockWaitsEntered() { return database.lockWaitsEntered(); }

    @Override
    public long lockWaitsActuallyBlocked() { return database.lockWaitsActuallyBlocked(); }

    @Override
    public long lockWaitBlockedNanos() { return database.lockWaitBlockedNanos(); }

    @Override
    public long lockWaitsGranted() { return database.lockWaitsGranted(); }

    @Override
    public long lockWaitsTimedOut() { return database.lockWaitsTimedOut(); }

    @Override
    public long lockWaitsDeadlocked() { return database.lockWaitsDeadlocked(); }

    @Override
    public long lockWaitsCancelled() { return database.lockWaitsCancelled(); }

    @Override
    public boolean lockEscalationSupported() { return database.lockEscalationSupported(); }

    @Override
    public long lockEscalationCount() { return database.lockEscalationCount(); }

    @Override
    public synchronized StatusCode createSession(SessionOpenResult result) {
      return createSession(null, result);
    }

    @Override
    public synchronized StatusCode createSession(
        SessionAuthorizer authorizer, SessionOpenResult result) {
      if (result == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (closed) {
        return StatusCode.CLOSED;
      }
      StatusCode cleanupHealth = terminalCleanup.health();
      if (!cleanupHealth.isOk() && !cleanupHealth.isRetryable()) return cleanupHealth;
      SqlSessionOpenResult opened;
      try {
        opened = new SqlSessionOpenResult();
      } catch (OutOfMemoryError failure) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      StatusCode status = authorizer == null ? SqlSession.create(database, opened)
          : SqlSession.create(database, authorizer, opened);
      if (!status.isOk()) {
        return status;
      }
      EngineSession engineSession;
      try {
        engineSession = new EngineSession(this, opened.session());
      } catch (OutOfMemoryError failure) {
        StatusCode cleanup = opened.session().close();
        return cleanup.isOk() ? StatusCode.RESOURCE_EXHAUSTED : cleanup;
      }
      status = result.complete(engineSession);
      if (!status.isOk()) {
        StatusCode cleanup = opened.session().close();
        if (!cleanup.isOk()) return cleanup;
      }
      if (status.isOk()) {
        openSessions++;
      }
      return status;
    }

    @Override
    public StatusCode deferTerminalClose(RiverSession session) {
      return session instanceof TerminalSessionCleanupTarget target
          ? terminalCleanup.transfer(target) : StatusCode.INVALID_EXTERNAL_INPUT;
    }

    @Override
    public synchronized StatusCode close() {
      if (closed) {
        return StatusCode.CLOSED;
      }
      StatusCode cleanup = terminalCleanup.health();
      if (!cleanup.isOk()) {
        if (!cleanup.isRetryable()) terminalCleanup.retryFence();
        return StatusCode.RETRY;
      }
      if (openSessions != 0) {
        return StatusCode.CONFLICT;
      }
      StatusCode status = terminalCleanup.close();
      if (status.isOk()) status = database.close();
      if (status.isOk()) {
        closed = true;
      }
      return status;
    }

    private synchronized void sessionClosed() {
      openSessions--;
    }
  }

  private static final class EngineSession
      implements RiverSession, TerminalSessionCleanupTarget {
    private final EngineDatabase owner;
    private final SqlSession session;
    private final SqlExecutionResult execution = new SqlExecutionResult();
    private final SqlScanCursor scan = new SqlScanCursor();
    private final SqlScanRowResult scanRow = new SqlScanRowResult();
    private final SqlPublicResultPublisher resultPublisher = new SqlPublicResultPublisher();
    private final EngineQuery query = new EngineQuery();
    private final SessionHandleDirectory handles;
    private final RetainedPreparedStatements prepared;
    private final RetainedTransactionPrograms transactionPrograms;
    private final TransactionProgramExecutor programExecutor;
    private final io.riverdb.engine.sql.SqlPreparedValidationResult preparedValidation =
        new io.riverdb.engine.sql.SqlPreparedValidationResult();
    private boolean closed;
    private boolean terminalTransferred;
    private TerminalSessionCleanupTarget terminalNext;

    private EngineSession(EngineDatabase database, SqlSession sqlSession) {
      owner = database;
      session = sqlSession;
      handles = new SessionHandleDirectory(sqlSession);
      prepared = new RetainedPreparedStatements(sqlSession, handles);
      transactionPrograms = new RetainedTransactionPrograms(sqlSession, prepared, handles);
      programExecutor = new TransactionProgramExecutor(sqlSession);
    }

    @Override
    public synchronized StatusCode configureTransactionDiagnostics(
        long diagnosticTag, long diagnosticStepTag, long metricsEpoch) {
      if (closed) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      return session.configureTransactionDiagnostics(
          diagnosticTag, diagnosticStepTag, metricsEpoch);
    }

    @Override
    public synchronized StatusCode prepare(String sql, PreparedOpenResult result) {
      if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      result.reset();
      if (closed) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      StatusCode status = session.validatePrepared(sql, session, preparedValidation);
      if (status.isOk()) status = prepared.open(preparedValidation, result);
      StatusCode released = preparedValidation.reset();
      return status.isOk() ? released : status;
    }

    @Override
    public synchronized StatusCode executePrepared(
        long handle, ParameterSet parameters, CommandResult result) {
      if (parameters == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      result.reset();
      if (closed) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      SqlPreparedPlan plan = prepared.resolve(handle, false);
      if (plan == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      StatusCode status = session.executePrepared(plan, parameters, execution);
      return status.isOk() ? copyExecution(result) : status;
    }

    @Override
    public synchronized StatusCode beginPreparedQuery(
        long handle, ParameterSet parameters, QueryOpenResult result) {
      if (parameters == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      result.reset();
      if (closed) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      SqlPreparedPlan plan = prepared.resolve(handle, true);
      return plan == null
          ? StatusCode.INVALID_EXTERNAL_INPUT
          : beginPreparedQuery(plan, parameters, result);
    }

    @Override
    public synchronized StatusCode closePrepared(long handle) {
      if (closed) return StatusCode.CLOSED;
      return query.active ? StatusCode.CONFLICT : prepared.close(handle);
    }

    @Override
    public synchronized StatusCode prepareProgram(
        TransactionProgram program, ProgramOpenResult result) {
      if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      result.reset();
      if (closed) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      return transactionPrograms.open(program, result);
    }

    @Override
    public synchronized StatusCode executeProgram(
        long programHandle,
        IsolationLevel isolationLevel,
        TransactionProgramArguments arguments,
        TransactionProgramResult result) {
      if (isolationLevel == null || result == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (closed) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      RetainedTransactionProgram program = transactionPrograms.resolve(programHandle);
      return program == null
          ? StatusCode.INVALID_EXTERNAL_INPUT
          : programExecutor.execute(program, isolationLevel, arguments, result);
    }

    @Override
    public synchronized StatusCode closeProgram(long programHandle) {
      if (closed) return StatusCode.CLOSED;
      return query.active ? StatusCode.CONFLICT : transactionPrograms.close(programHandle);
    }

    @Override
    public synchronized StatusCode execute(String sql, CommandResult result) {
      return execute(sql, null, result, false);
    }

    @Override
    public synchronized StatusCode execute(
        String sql, ParameterSet parameters, CommandResult result) {
      return execute(sql, parameters, result, true);
    }

    private StatusCode execute(
        String sql,
        ParameterSet parameters,
        CommandResult result,
        boolean typed) {
      if (result == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (closed) {
        return StatusCode.CLOSED;
      }
      if (typed && parameters == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = typed
          ? session.execute(sql, parameters, execution)
          : session.execute(sql, execution);
      return status.isOk() ? copyExecution(result) : status;
    }

    @Override
    public synchronized StatusCode beginQuery(String sql, QueryOpenResult result) {
      return beginQuery(sql, null, result, false);
    }

    @Override
    public synchronized StatusCode beginQuery(
        String sql, ParameterSet parameters, QueryOpenResult result) {
      return beginQuery(sql, parameters, result, true);
    }

    private StatusCode beginQuery(
        String sql, ParameterSet parameters, QueryOpenResult result, boolean typed) {
      if (result == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (closed) {
        return StatusCode.CLOSED;
      }
      if (query.active) {
        return StatusCode.CONFLICT;
      }
      if (typed && parameters == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = scan.reset();
      if (status.isOk()) {
        status = typed
            ? session.beginScan(sql, parameters, scan)
            : session.beginScan(sql, scan);
      }
      if (status.isOk()) {
        status = query.prepare();
      }
      if (status.isOk()) {
        query.active = true;
        status = result.complete(query);
      }
      if (!status.isOk() && scan.isActive()) {
        query.active = false;
        StatusCode close = session.closeScan(scan, execution);
        if (!close.isOk()) status = close;
      }
      return status;
    }

    private StatusCode beginPreparedQuery(
        SqlPreparedPlan plan, ParameterSet parameters, QueryOpenResult result) {
      if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      result.reset();
      if (closed) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      StatusCode status = scan.reset();
      if (status.isOk()) status = session.beginPreparedScan(plan, parameters, scan);
      if (status.isOk()) status = query.prepare();
      if (status.isOk()) {
        query.active = true;
        status = result.complete(query);
      }
      if (!status.isOk() && scan.isActive()) {
        query.active = false;
        StatusCode close = session.closeScan(scan, execution);
        if (!close.isOk()) status = close;
      }
      return status;
    }

    @Override
    public synchronized StatusCode close() {
      return terminalTransferred ? StatusCode.NOT_OWNER : closeOwned();
    }

    @Override
    public synchronized boolean transferToTerminalCleanup(Object databaseOwner) {
      if (databaseOwner != owner || closed || terminalTransferred) return false;
      terminalTransferred = true;
      return true;
    }

    @Override
    public synchronized StatusCode retryTerminalClose() {
      return !terminalTransferred ? StatusCode.NOT_OWNER : closeOwned();
    }

    @Override
    public synchronized TerminalSessionCleanupTarget terminalCleanupNext() {
      return terminalNext;
    }

    @Override
    public synchronized void terminalCleanupNext(TerminalSessionCleanupTarget next) {
      terminalNext = next;
    }

    private StatusCode closeOwned() {
      if (closed) {
        return StatusCode.CLOSED;
      }
      StatusCode status = StatusCode.OK;
      if (query.active) {
        status = session.closeScan(scan, execution);
        if (!scan.isActive()) query.active = false;
      }
      StatusCode validationReleased = preparedValidation.reset();
      if (status.isOk()) status = validationReleased;
      if (status.isOk()) status = programExecutor.close();
      if (status.isOk()) status = transactionPrograms.clear();
      if (status.isOk()) status = prepared.clear();
      if (status.isOk()) status = handles.clear();
      if (status.isOk()) {
        status = session.close();
      }
      if (status.isOk()) {
        closed = true;
        owner.sessionClosed();
      }
      return status;
    }

    private StatusCode copyExecution(CommandResult result) {
      return resultPublisher.publish(execution, result);
    }

    private final class EngineQuery implements RiverQuery {
      private final EmbeddedQueryMetadata metadata = new EmbeddedQueryMetadata();
      private boolean active;

      private StatusCode prepare() {
        StatusCode status = metadata.prepare(session, scan);
        return status.isOk() ? resultPublisher.reserve(metadata.columnCount()) : status;
      }

      @Override
      public StatusCode next(RowResult result) {
        synchronized (EngineSession.this) {
          return nextSerialized(result);
        }
      }

      private StatusCode nextSerialized(RowResult result) {
        if (result == null) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (closed) {
          return StatusCode.CLOSED;
        }
        if (!active) {
          return StatusCode.CONFLICT;
        }
        StatusCode status = result.reserve(metadata, null);
        if (!status.isOk()) return status;
        result.reset();
        status = session.nextScan(scan, scanRow);
        if (status == StatusCode.CONFLICT && !scanRow.isAvailable()) {
          return StatusCode.OK;
        }
        if (!status.isOk()) {
          return status;
        }
        return resultPublisher.publish(scanRow, result);
      }

      @Override
      public StatusCode close(CommandResult result) {
        synchronized (EngineSession.this) {
          return closeSerialized(result);
        }
      }

      private StatusCode closeSerialized(CommandResult result) {
        if (result == null) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        result.reset();
        if (closed) {
          return StatusCode.CLOSED;
        }
        if (!active) {
          return StatusCode.CONFLICT;
        }
        StatusCode status = session.closeScan(scan, execution);
        boolean physicalClosed = !scan.isActive();
        if (physicalClosed) {
          active = false;
          if (status.isOk()) status = copyExecution(result);
        }
        return status;
      }

      @Override
      public boolean isActive() {
        synchronized (EngineSession.this) {
          return active;
        }
      }

      @Override
      public QueryMetadata metadata() {
        return metadata;
      }

      @Override
      public int columnCount() {
        return scan.projectedColumnCount();
      }

      @Override
      public CharSequence columnName(int index) {
        return session.scanColumnName(scan, index);
      }

      @Override
      public int columnTypeDescriptor(int index) {
        return session.scanColumnTypeDescriptor(scan, index);
      }

      @Override
      public boolean columnIsNullable(int index) {
        return session.scanColumnIsNullable(scan, index);
      }

      @Override
      public long rowsReturned() {
        return scan.rowsReturned();
      }
    }
  }
}
