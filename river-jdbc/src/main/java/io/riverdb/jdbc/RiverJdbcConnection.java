package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.client.RiverClientConnection;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverSession;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;

/** One JDBC connection owns one ordered remote River session. */
final class RiverJdbcConnection extends AbstractConnection {
  private final RiverClientConnection client;
  private final RiverSession session;
  private final CommandResult transactionResult = new CommandResult();
  private RiverJdbcStatement statement;
  private boolean autoCommit = true;
  private boolean transactionActive;
  private boolean closed;
  private int isolation = Connection.TRANSACTION_REPEATABLE_READ;

  RiverJdbcConnection(RiverClientConnection remoteClient, RiverSession remoteSession) {
    client = remoteClient;
    session = remoteSession;
  }

  @Override
  public Statement createStatement() throws SQLException {
    return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
  }

  @Override
  public Statement createStatement(int type, int concurrency) throws SQLException {
    return createStatement(type, concurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT);
  }

  @Override
  public Statement createStatement(int type, int concurrency, int holdability)
      throws SQLException {
    requireOpen();
    if (type != ResultSet.TYPE_FORWARD_ONLY
        || concurrency != ResultSet.CONCUR_READ_ONLY
        || holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
      throw JdbcExceptions.unsupported();
    }
    if (statement != null) {
      throw JdbcExceptions.failure(StatusCode.CONFLICT, "create statement");
    }
    statement = new RiverJdbcStatement(this, session);
    return statement;
  }

  @Override
  public String nativeSQL(String sql) throws SQLException {
    requireOpen();
    if (sql == null) {
      throw JdbcExceptions.invalid("SQL must not be null");
    }
    return sql;
  }

  @Override
  public void setAutoCommit(boolean enabled) throws SQLException {
    requireOpen();
    if (autoCommit == enabled) {
      return;
    }
    if (enabled && transactionActive) {
      closeTransactionResult();
      finishTransaction("COMMIT", "enable auto-commit");
    }
    autoCommit = enabled;
  }

  @Override
  public boolean getAutoCommit() throws SQLException {
    requireOpen();
    return autoCommit;
  }

  @Override
  public void commit() throws SQLException {
    requireManualTransaction("commit");
    if (transactionActive) {
      closeTransactionResult();
      finishTransaction("COMMIT", "commit");
    }
  }

  @Override
  public void rollback() throws SQLException {
    requireManualTransaction("rollback");
    if (transactionActive) {
      closeTransactionResult();
      finishTransaction("ROLLBACK", "rollback");
    }
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    SQLException closeFailure = null;
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException failure) {
        closeFailure = failure;
      }
    }
    StatusCode sessionStatus = session.close();
    StatusCode connectionStatus = client.close();
    closed = true;
    if (closeFailure != null) {
      throw closeFailure;
    } else if (!sessionStatus.isOk() && sessionStatus != StatusCode.CLOSED) {
      throw JdbcExceptions.failure(sessionStatus, "close session");
    } else if (!connectionStatus.isOk() && connectionStatus != StatusCode.CLOSED) {
      throw JdbcExceptions.failure(connectionStatus, "close connection");
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void setReadOnly(boolean readOnly) throws SQLException {
    requireOpen();
    if (readOnly) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public boolean isReadOnly() throws SQLException {
    requireOpen();
    return false;
  }

  @Override
  public void setCatalog(String catalog) throws SQLException {
    requireOpen();
    if (catalog != null) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public String getCatalog() throws SQLException {
    requireOpen();
    return null;
  }

  @Override
  public void setTransactionIsolation(int level) throws SQLException {
    requireOpen();
    if (transactionActive) {
      throw JdbcExceptions.failure(StatusCode.CONFLICT, "change transaction isolation");
    }
    if (level != Connection.TRANSACTION_REPEATABLE_READ
        && level != Connection.TRANSACTION_SERIALIZABLE) {
      throw JdbcExceptions.unsupported();
    }
    isolation = level;
  }

  @Override
  public int getTransactionIsolation() throws SQLException {
    requireOpen();
    return isolation;
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
  public Map<String, Class<?>> getTypeMap() throws SQLException {
    requireOpen();
    return Collections.emptyMap();
  }

  @Override
  public void setHoldability(int holdability) throws SQLException {
    requireOpen();
    if (holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public int getHoldability() throws SQLException {
    requireOpen();
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public boolean isValid(int timeout) throws SQLException {
    if (timeout < 0) {
      throw JdbcExceptions.invalid("timeout must not be negative");
    }
    return !closed;
  }

  @Override
  public void setSchema(String schema) throws SQLException {
    requireOpen();
    if (schema != null) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public String getSchema() throws SQLException {
    requireOpen();
    return null;
  }

  @Override
  public int getNetworkTimeout() throws SQLException {
    requireOpen();
    return 30_000;
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

  void beforeExecution() throws SQLException {
    requireOpen();
    if (!autoCommit && !transactionActive) {
      String begin = isolation == Connection.TRANSACTION_SERIALIZABLE
          ? "BEGIN SERIALIZABLE" : "BEGIN";
      transactionResult.reset();
      JdbcExceptions.require(session.execute(begin, transactionResult), "begin transaction");
      transactionActive = true;
    }
  }

  void commandCompleted(CommandResult result) {
    transactionActive = result.transactionActive();
  }

  void statementClosed(RiverJdbcStatement closedStatement) {
    if (statement == closedStatement) {
      statement = null;
    }
  }

  private void finishTransaction(String sql, String operation) throws SQLException {
    transactionResult.reset();
    JdbcExceptions.require(session.execute(sql, transactionResult), operation);
    transactionActive = transactionResult.transactionActive();
  }

  private void closeTransactionResult() throws SQLException {
    if (statement != null) {
      statement.closeOpenResult();
    }
  }

  private void requireManualTransaction(String operation) throws SQLException {
    requireOpen();
    if (autoCommit) {
      throw JdbcExceptions.failure(StatusCode.CONFLICT, operation + " in auto-commit mode");
    }
  }

  private void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("connection");
    }
  }
}
