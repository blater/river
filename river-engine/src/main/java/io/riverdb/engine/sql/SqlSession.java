package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.relational.RelationalDatabase;

/** Public SQL session façade and durable result-delivery boundary. */
public final class SqlSession implements SqlRetainedBudget {
  private final SqlSessionExecutionCoordinator coordinator;
  private StatusCode deliver(StatusCode status) {
    if (coordinator.programTransactionActive()
        || !status.isOk() && status != StatusCode.CONFLICT) return status;
    StatusCode durability = coordinator.awaitDurability();
    return durability.isOk() ? status : durability;
  }

  SqlSession(SqlSessionExecutionCoordinator sessionCoordinator) {
    coordinator = sessionCoordinator;
  }

  public static StatusCode create(
      RelationalDatabase database,
      SqlSessionOpenResult result) {
    return create(database, null, result);
  }

  public static String timeZoneDatabaseVersion() {
    return SqlTemporalContext.timeZoneDatabaseVersion();
  }

  public static StatusCode create(
      RelationalDatabase database,
      SessionAuthorizer authorizer,
      SqlSessionOpenResult result) {
    if (database == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    return SqlSessionFactory.create(database, authorizer, result);
  }

  public StatusCode execute(String sql, SqlExecutionResult result) {
    return deliver(coordinator.execute(sql, result));
  }

  public StatusCode execute(
      String sql, ParameterSet parameters, SqlExecutionResult result) {
    return deliver(coordinator.execute(sql, parameters, result));
  }

  public StatusCode validatePrepared(
      String sql, SqlRetainedBudget budget, SqlPreparedValidationResult result) {
    return coordinator.validatePrepared(sql, budget, result);
  }

  public StatusCode executePrepared(
      SqlPreparedPlan plan, ParameterSet parameters, SqlExecutionResult result) {
    return deliver(coordinator.executePrepared(plan, parameters, result));
  }

  public StatusCode beginScan(String sql, SqlScanCursor cursor) {
    return deliver(coordinator.beginScan(sql, cursor));
  }

  public StatusCode beginScan(
      String sql, ParameterSet parameters, SqlScanCursor cursor) {
    return deliver(coordinator.beginScan(sql, parameters, cursor));
  }

  public StatusCode beginPreparedScan(
      SqlPreparedPlan plan, ParameterSet parameters, SqlScanCursor cursor) {
    return deliver(coordinator.beginPreparedScan(plan, parameters, cursor));
  }

  public StatusCode executePreparedSingleton(
      SqlPreparedPlan plan,
      ParameterSet parameters,
      SqlScanCursor cursor,
      SqlExecutionResult result,
      SqlPreparedQueryPath path) {
    return deliver(
        coordinator.executePreparedSingleton(plan, parameters, cursor, result, path));
  }

  public StatusCode configureTransactionDiagnostics(
      long diagnosticTag, long diagnosticStepTag, long metricsEpoch) {
    return coordinator.configureTransactionDiagnostics(
        diagnosticTag, diagnosticStepTag, metricsEpoch);
  }

  public StatusCode updateTransactionDiagnosticStep(long diagnosticStepTag) {
    return coordinator.updateTransactionDiagnosticStep(diagnosticStepTag);
  }

  public StatusCode nextScan(SqlScanCursor cursor, SqlScanRowResult result) {
    return deliver(coordinator.nextScan(cursor, result));
  }

  public CharSequence scanColumnName(SqlScanCursor cursor, int index) {
    return coordinator.scanColumnName(cursor, index);
  }

  public int scanColumnTypeDescriptor(SqlScanCursor cursor, int index) {
    return coordinator.scanColumnTypeDescriptor(cursor, index);
  }

  public boolean scanColumnIsNullable(SqlScanCursor cursor, int index) {
    return coordinator.scanColumnIsNullable(cursor, index);
  }

  public StatusCode closeScan(
      SqlScanCursor cursor, SqlExecutionResult result) {
    return deliver(coordinator.closeScan(cursor, result));
  }

  public StatusCode beginProgram(
      IsolationLevel isolationLevel, SqlExecutionResult result) {
    return coordinator.beginProgram(isolationLevel, result);
  }

  public StatusCode commitProgram(SqlExecutionResult result) {
    return coordinator.commitProgram(result);
  }

  public StatusCode abortProgram(SqlExecutionResult result) {
    return coordinator.abortProgram(result);
  }

  public boolean programTransactionActive() {
    return coordinator.programTransactionActive();
  }

  public boolean matchesCatalogGeneration(long expected) {
    return coordinator.matchesCatalogGeneration(expected);
  }

  public StatusCode close() {
    return coordinator.close();
  }

  @Override
  public StatusCode reserveRetainedBytes(long bytes) {
    return coordinator.reserveRetainedBytes(bytes);
  }

  @Override
  public StatusCode releaseRetainedBytes(long bytes) {
    return coordinator.releaseRetainedBytes(bytes);
  }

  long retainedShapeBytes() { return coordinator.retainedShapeBytes(); }
  long maximumShapeBytes() { return coordinator.maximumShapeBytes(); }
  long preparedCompiles() { return coordinator.preparedCompiles(); }
  long preparedExecutions() { return coordinator.preparedExecutions(); }
  long preparedRecompiles() { return coordinator.preparedRecompiles(); }
}
