package io.riverdb.jdbc;

import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.RiverSession;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Bounded prepared statement sending typed values separately from SQL text. */
final class RiverJdbcPreparedStatement extends RiverJdbcTypedPreparedStatement {
  static final int MAXIMUM_PARAMETERS = ParameterSet.MAXIMUM_PARAMETERS;
  static final int MAXIMUM_RENDERED_CHARACTERS = 16 * 1024;

  private final String template;
  private final boolean returnGeneratedKeys;

  RiverJdbcPreparedStatement(
      RiverJdbcConnection owner,
      RiverSession session,
      String sql) throws SQLException {
    this(owner, session, sql, false);
  }

  RiverJdbcPreparedStatement(
      RiverJdbcConnection owner,
      RiverSession session,
      String sql,
      boolean generatedKeys) throws SQLException {
    super(owner, session, parameterCount(sql));
    template = sql;
    returnGeneratedKeys = generatedKeys;
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    return executeQuerySql(template, boundParameters());
  }

  @Override
  public int executeUpdate() throws SQLException {
    return executeUpdateSql(
        template, boundParameters(), returnGeneratedKeys);
  }

  @Override
  public boolean execute() throws SQLException {
    return executeSql(template, boundParameters(), returnGeneratedKeys);
  }

  @Override
  public void addBatch() throws SQLException {
    requireBatchCapacity();
    addSqlBatch(template, snapshotParameters());
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    return executeUpdate();
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    throw JdbcExceptions.invalid("prepared statements do not accept execution SQL");
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    throw JdbcExceptions.invalid("prepared statements do not accept execution SQL");
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    throw JdbcExceptions.invalid("prepared statements do not accept execution SQL");
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    throw JdbcExceptions.invalid("prepared statements do not accept batch SQL");
  }

  private static int parameterCount(String sql) throws SQLException {
    if (sql == null || sql.isEmpty()) {
      throw JdbcExceptions.invalid("prepared SQL must not be empty");
    }
    if (sql.length() > MAXIMUM_RENDERED_CHARACTERS) {
      throw JdbcExceptions.invalid("prepared SQL exceeds the bounded protocol payload");
    }
    int count = RiverJdbcParameterTypes.countMarkers(sql);
    if (count > MAXIMUM_PARAMETERS) {
      throw JdbcExceptions.invalid("prepared SQL has too many parameters");
    }
    return count;
  }
}
