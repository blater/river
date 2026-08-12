package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSessionOpenResult;

/** Public SQL session façade; mutable execution state is owned by its components. */
public final class SqlSession {
  private final SqlSessionExecutionCoordinator coordinator;

  private SqlSession(SqlSessionExecutionCoordinator sessionCoordinator) {
    coordinator = sessionCoordinator;
  }

  public static StatusCode create(
      RelationalDatabase database,
      SqlSessionOpenResult result) {
    if (database == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    StatusCode status = database.createSession(sessionResult);
    if (status.isOk()) {
      result.set(new SqlSession(
          new SqlSessionExecutionCoordinator(database, sessionResult.session())));
    }
    return status;
  }

  public StatusCode execute(String sql, SqlExecutionResult result) {
    return coordinator.execute(sql, result);
  }

  public StatusCode beginScan(String sql, SqlScanCursor cursor) {
    return coordinator.beginScan(sql, cursor);
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

  public StatusCode closeScan(
      SqlScanCursor cursor, SqlExecutionResult result) {
    return coordinator.closeScan(cursor, result);
  }

  public StatusCode close() {
    return coordinator.close();
  }
}
