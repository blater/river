package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.ParameterSet;
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
  String[] batch = new String[0];
  ParameterSet[] batchParameters;
  private RiverJdbcResultSet resultSet;
  private RiverGeneratedKeysResultSet generatedKeysResultSet;
  private long generatedKey;
  private boolean generatedKeyAvailable;
  private int updateCount = -1;
  private boolean closeOnCompletion;
  private boolean closed;
  int batchCount;

  RiverJdbcStatement(RiverJdbcConnection owner, RiverSession remoteSession) {
    connection = owner;
    session = remoteSession;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    return executeQuerySql(sql, null);
  }

  final ResultSet executeQuerySql(String sql, ParameterSet parameters)
      throws SQLException {
    requireOpen();
    closeCurrentResult();
    connection.beforeExecution();
    openedQuery.reset();
    StatusCode status = parameters == null
        ? session.beginQuery(sql, openedQuery)
        : session.beginQuery(sql, parameters, openedQuery);
    JdbcExceptions.require(status, "execute query");
    RiverQuery query = openedQuery.query();
    resultSet = new RiverJdbcResultSet(this, query);
    updateCount = -1;
    return resultSet;
  }

  final ResultSet executePreparedQuery(long handle, ParameterSet parameters)
      throws SQLException {
    requireOpen();
    closeCurrentResult();
    connection.beforeExecution();
    openedQuery.reset();
    StatusCode status = session.beginPreparedQuery(handle, parameters, openedQuery);
    JdbcExceptions.require(status, "execute prepared query");
    resultSet = new RiverJdbcResultSet(this, openedQuery.query());
    updateCount = -1;
    return resultSet;
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    return executeUpdateSql(sql, null, false);
  }

  final int executeUpdateSql(
      String sql, ParameterSet parameters, boolean returnGeneratedKeys)
      throws SQLException {
    requireOpen();
    closeCurrentResult();
    connection.beforeExecution();
    command.reset();
    StatusCode status = parameters == null
        ? session.execute(sql, command) : session.execute(sql, parameters, command);
    JdbcExceptions.require(status, "execute update");
    if (command.rowAvailable()) {
      throw JdbcExceptions.invalid("query SQL must use executeQuery");
    }
    connection.commandCompleted(command);
    updateCount = command.affectedRows();
    generatedKeyAvailable = returnGeneratedKeys && command.key() > 0;
    generatedKey = generatedKeyAvailable ? command.key() : 0;
    return updateCount;
  }

  final int executePreparedUpdate(
      long handle, ParameterSet parameters, boolean returnGeneratedKeys)
      throws SQLException {
    requireOpen();
    closeCurrentResult();
    connection.beforeExecution();
    command.reset();
    StatusCode status = session.executePrepared(handle, parameters, command);
    JdbcExceptions.require(status, "execute prepared update");
    if (command.rowAvailable()) {
      throw JdbcExceptions.invalid("query handle must use executeQuery");
    }
    connection.commandCompleted(command);
    updateCount = command.affectedRows();
    generatedKeyAvailable = returnGeneratedKeys && command.key() > 0;
    generatedKey = generatedKeyAvailable ? command.key() : 0;
    return updateCount;
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    return executeSql(sql, null, false);
  }

  final boolean executeSql(
      String sql, ParameterSet parameters, boolean returnGeneratedKeys)
      throws SQLException {
    if (isQuery(sql)) {
      if (returnGeneratedKeys) {
        throw JdbcExceptions.invalid("generated keys require update SQL");
      }
      executeQuerySql(sql, parameters);
      return true;
    }
    executeUpdateSql(sql, parameters, returnGeneratedKeys);
    return false;
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    closeCurrentResult();
    JdbcExceptions.require(releaseRetainedPrepared(), "close prepared statement");
    clearBatchEntries();
    closed = true;
    connection.statementClosed(this);
  }

  StatusCode releaseRetainedPrepared() { return StatusCode.OK; }

  @Override
  public void cancel() throws SQLException {
    requireOpen();
    connection.cancelCurrentOperation();
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
  public void addBatch(String sql) throws SQLException {
    requireOpen();
    if (sql == null) {
      throw JdbcExceptions.invalid("batch SQL must not be null");
    }
    addSqlBatch(sql);
  }

  @Override
  public void clearBatch() throws SQLException {
    requireOpen();
    clearBatchEntries();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    requireOpen();
    closeCurrentResult();
    return RiverJdbcBatchExecutor.execute(this);
  }

  @Override
  public long[] executeLargeBatch() throws SQLException {
    int[] updates = executeBatch();
    long[] large = new long[updates.length];
    for (int index = 0; index < updates.length; index++) {
      large[index] = updates[index];
    }
    return large;
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
    if (generatedKeys != NO_GENERATED_KEYS && generatedKeys != RETURN_GENERATED_KEYS) {
      throw JdbcExceptions.unsupported();
    }
    return executeUpdateSql(sql, null, generatedKeys == RETURN_GENERATED_KEYS);
  }

  @Override
  public boolean execute(String sql, int generatedKeys) throws SQLException {
    if (generatedKeys != NO_GENERATED_KEYS && generatedKeys != RETURN_GENERATED_KEYS) {
      throw JdbcExceptions.unsupported();
    }
    return executeSql(sql, null, generatedKeys == RETURN_GENERATED_KEYS);
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    requireOpen();
    if (generatedKeysResultSet == null) {
      generatedKeysResultSet = new RiverGeneratedKeysResultSet(
          this, generatedKey, generatedKeyAvailable);
    }
    return generatedKeysResultSet;
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

  void addSqlBatch(String sql) throws SQLException {
    addSqlBatch(sql, null);
  }

  void addSqlBatch(String sql, ParameterSet parameters) throws SQLException {
    ensureBatchCapacity();
    batch[batchCount] = sql;
    if (parameters != null) {
      if (batchParameters == null) {
        batchParameters = new ParameterSet[batch.length];
      }
      batchParameters[batchCount] = parameters;
    }
    batchCount++;
  }

  void addPreparedBatch(ParameterSet parameters) throws SQLException {
    ensureBatchCapacity();
    if (batchParameters == null) {
      batchParameters = new ParameterSet[batch.length];
    }
    batchParameters[batchCount++] = parameters;
  }

  final void ensureBatchCapacity() throws SQLException {
    if (batchCount < batch.length) return;
    if (batchCount == Integer.MAX_VALUE) {
      throw JdbcExceptions.failure(
          StatusCode.RESOURCE_EXHAUSTED,
          "add batch entry");
    }
    int required = batchCount + 1;
    int grown = batch.length == 0 ? 16
        : batch.length >= Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : batch.length << 1;
    int capacity = Math.max(required, grown);
    try {
      String[] grownBatch = java.util.Arrays.copyOf(batch, capacity);
      ParameterSet[] grownParameters = batchParameters == null ? null
          : java.util.Arrays.copyOf(batchParameters, capacity);
      batch = grownBatch;
      batchParameters = grownParameters;
    } catch (OutOfMemoryError exhausted) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "add batch entry");
    }
  }

  private void closeCurrentResult() throws SQLException {
    RiverJdbcResultSet current = resultSet;
    if (current != null) {
      current.close();
    }
    RiverGeneratedKeysResultSet currentKeys = generatedKeysResultSet;
    generatedKeysResultSet = null;
    generatedKey = 0;
    generatedKeyAvailable = false;
    if (currentKeys != null) {
      currentKeys.close();
    }
  }

  private void clearBatchEntries() {
    for (int index = 0; index < batchCount; index++) {
      batch[index] = null;
      releaseBatchParameters(index);
    }
    batchCount = 0;
  }

  void releaseBatchParameters(int index) {
    if (batchParameters == null) return;
    ParameterSet parameters = batchParameters[index];
    batchParameters[index] = null;
    if (parameters != null) parameters.reset();
  }

  final void requireOpen() throws SQLException {
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
