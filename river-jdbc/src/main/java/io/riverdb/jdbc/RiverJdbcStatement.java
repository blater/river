package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/** One forward-only statement on the connection's ordered River session. */
class RiverJdbcStatement extends AbstractStatement {
  private final RiverJdbcConnection connection;
  private final RiverSession session;
  private final CommandResult command = new CommandResult();
  private final QueryOpenResult openedQuery = new QueryOpenResult();
  private RiverJdbcResultSet resultSet;
  private int updateCount = -1;
  private boolean closeOnCompletion;
  private boolean closed;

  RiverJdbcStatement(RiverJdbcConnection owner, RiverSession remoteSession) {
    connection = owner;
    session = remoteSession;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    requireOpen();
    closeCurrentResult();
    connection.beforeExecution();
    openedQuery.reset();
    JdbcExceptions.require(session.beginQuery(sql, openedQuery), "execute query");
    RiverQuery query = openedQuery.query();
    resultSet = new RiverJdbcResultSet(this, query, query.columnCount());
    updateCount = -1;
    return resultSet;
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    requireOpen();
    closeCurrentResult();
    connection.beforeExecution();
    command.reset();
    JdbcExceptions.require(session.execute(sql, command), "execute update");
    if (command.rowAvailable()) {
      throw JdbcExceptions.invalid("query SQL must use executeQuery");
    }
    connection.commandCompleted(command);
    updateCount = command.affectedRows();
    return updateCount;
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    if (isQuery(sql)) {
      executeQuery(sql);
      return true;
    }
    executeUpdate(sql);
    return false;
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    closeCurrentResult();
    closed = true;
    connection.statementClosed(this);
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    requireOpen();
    return 0;
  }

  @Override
  public void setMaxFieldSize(int maximum) throws SQLException {
    requireOpen();
    if (maximum != 0) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public int getMaxRows() throws SQLException {
    requireOpen();
    return 0;
  }

  @Override
  public void setMaxRows(int maximum) throws SQLException {
    requireOpen();
    if (maximum != 0) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public void setEscapeProcessing(boolean enabled) throws SQLException {
    requireOpen();
    if (enabled) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    requireOpen();
    return 0;
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    requireOpen();
    if (seconds != 0) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public java.sql.SQLWarning getWarnings() throws SQLException {
    requireOpen();
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {
    requireOpen();
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    requireOpen();
    return resultSet;
  }

  @Override
  public int getUpdateCount() throws SQLException {
    requireOpen();
    return updateCount;
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return getMoreResults(CLOSE_CURRENT_RESULT);
  }

  @Override
  public boolean getMoreResults(int behavior) throws SQLException {
    requireOpen();
    if (behavior != CLOSE_CURRENT_RESULT && behavior != CLOSE_ALL_RESULTS) {
      throw JdbcExceptions.unsupported();
    }
    closeCurrentResult();
    updateCount = -1;
    return false;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    requireOpen();
    if (direction != ResultSet.FETCH_FORWARD) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public int getFetchDirection() throws SQLException {
    requireOpen();
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    requireOpen();
    if (rows < 0 || rows > 1) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public int getFetchSize() throws SQLException {
    requireOpen();
    return 1;
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    requireOpen();
    return ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public int getResultSetType() throws SQLException {
    requireOpen();
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public Connection getConnection() throws SQLException {
    requireOpen();
    return connection;
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    requireOpen();
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    requireOpen();
    if (poolable) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public boolean isPoolable() throws SQLException {
    requireOpen();
    return false;
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    requireOpen();
    closeOnCompletion = true;
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    requireOpen();
    return closeOnCompletion;
  }

  @Override
  public long getLargeUpdateCount() throws SQLException {
    return getUpdateCount();
  }

  @Override
  public long executeLargeUpdate(String sql) throws SQLException {
    return executeUpdate(sql);
  }

  @Override
  public int executeUpdate(String sql, int generatedKeys) throws SQLException {
    if (generatedKeys != NO_GENERATED_KEYS) {
      throw JdbcExceptions.unsupported();
    }
    return executeUpdate(sql);
  }

  @Override
  public boolean execute(String sql, int generatedKeys) throws SQLException {
    if (generatedKeys != NO_GENERATED_KEYS) {
      throw JdbcExceptions.unsupported();
    }
    return execute(sql);
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    if (type != null && type.isInstance(this)) {
      return type.cast(this);
    }
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isWrapperFor(Class<?> type) {
    return type != null && type.isInstance(this);
  }

  void queryCompleted(RiverJdbcResultSet completed, CommandResult result)
      throws SQLException {
    connection.commandCompleted(result);
    if (resultSet == completed) {
      resultSet = null;
    }
    if (closeOnCompletion && !closed) {
      close();
    }
  }

  void closeOpenResult() throws SQLException {
    closeCurrentResult();
  }

  private void closeCurrentResult() throws SQLException {
    RiverJdbcResultSet current = resultSet;
    if (current != null) {
      current.close();
    }
  }

  private void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("statement");
    }
  }

  private static boolean isQuery(String sql) throws SQLException {
    if (sql == null) {
      throw JdbcExceptions.invalid("SQL must not be null");
    }
    int index = 0;
    while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
      index++;
    }
    int remaining = sql.length() - index;
    return remaining >= 6
        && sql.regionMatches(true, index, "SELECT", 0, 6)
        && (remaining == 6 || Character.isWhitespace(sql.charAt(index + 6)));
  }
}
