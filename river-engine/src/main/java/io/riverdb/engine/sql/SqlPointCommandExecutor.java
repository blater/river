package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;

/** Owns the complete body of one non-streaming data command. */
final class SqlPointCommandExecutor {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlBinder binder;
  private final SqlDmlExecutor dml;
  private SqlQueryExecution queries;

  SqlPointCommandExecutor(
      RelationalSession relationalSession,
      BoundSqlStatement boundStatement,
      SqlBinder statementBinder,
      SqlDmlExecutor dmlExecutor) {
    session = relationalSession;
    bound = boundStatement;
    binder = statementBinder;
    dml = dmlExecutor;
  }

  void attachQueries(SqlQueryExecution queryExecution) {
    queries = queryExecution;
  }

  StatusCode execute(SqlExecutionResult result) {
    StatusCode status = StatusCode.OK;
    if (status.isOk()) {
      status = session.resolveTable(bound.command.tableName(), bound.table);
    }
    if (status.isOk()) {
      status = binder.bindDataCommand(
          bound.command,
          bound.query,
          bound,
          false,
          false,
          false,
          false);
    }
    if (status.isOk()) {
      status = binder.captureExecutableQuery(bound);
    }
    if (status.isOk()) {
      status = dml.handles(bound.command.type())
          ? dml.execute(bound.command, bound, result)
          : queries.executePointQuery(result);
    }
    return status;
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
