package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.relational.RelationalDatabase;

/** Public SQL session façade; mutable execution state is owned by its components. */
public final class SqlSession implements SqlRetainedBudget {
  private final SqlSessionExecutionCoordinator coordinator;

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
    return coordinator.execute(sql, result);
  }

  public StatusCode execute(
      String sql, ParameterSet parameters, SqlExecutionResult result) {
    return coordinator.execute(sql, parameters, result);
  }

  public StatusCode validatePrepared(
      String sql, SqlRetainedBudget budget, SqlPreparedValidationResult result) {
    return coordinator.validatePrepared(sql, budget, result);
  }

  public StatusCode executePrepared(
      SqlPreparedPlan plan, ParameterSet parameters, SqlExecutionResult result) {
    return coordinator.executePrepared(plan, parameters, result);
  }

  public StatusCode beginScan(String sql, SqlScanCursor cursor) {
    return coordinator.beginScan(sql, cursor);
  }

  public StatusCode beginScan(
      String sql, ParameterSet parameters, SqlScanCursor cursor) {
    return coordinator.beginScan(sql, parameters, cursor);
  }

  public StatusCode beginPreparedScan(
      SqlPreparedPlan plan, ParameterSet parameters, SqlScanCursor cursor) {
    return coordinator.beginPreparedScan(plan, parameters, cursor);
  }

  public StatusCode executePreparedSingleton(
      SqlPreparedPlan plan,
      ParameterSet parameters,
      SqlScanCursor cursor,
      SqlExecutionResult result,
      SqlPreparedQueryPath path) {
    return coordinator.executePreparedSingleton(plan, parameters, cursor, result, path);
  }

  public StatusCode nextScan(SqlScanCursor cursor, SqlScanRowResult result) {
    return coordinator.nextScan(cursor, result);
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
    return coordinator.closeScan(cursor, result);
  }

  public StatusCode beginProgram(SqlExecutionResult result) {
    return coordinator.beginProgram(result);
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
