package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.client.RiverClientConnection;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;

/** One JDBC connection owns one ordered remote River session. */
final class RiverJdbcConnection extends AbstractConnection {
  private static final int MAXIMUM_SAVEPOINT_NAME_LENGTH = 64;
  private static final int MAXIMUM_SAVEPOINTS = 3;

  private final RiverClientConnection client;
  private final RiverSession session;
  private final RiverDatabaseMetaData metadata;
  private final CommandResult transactionResult = new CommandResult();
  private final QueryOpenResult metadataQuery = new QueryOpenResult();
  private final RiverJdbcSavepoint[] savepoints =
      new RiverJdbcSavepoint[MAXIMUM_SAVEPOINTS];
  private RiverJdbcStatement statement;
  private AbstractResultSet metadataResult;
  private boolean autoCommit = true;
  private boolean transactionActive;
  private boolean closed;
  private int isolation = Connection.TRANSACTION_REPEATABLE_READ;
  private int nextSavepointId = 1;
  private int savepointCount;

  RiverJdbcConnection(
      RiverClientConnection remoteClient,
      RiverSession remoteSession,
      String url) {
    client = remoteClient;
    session = remoteSession;
    metadata = new RiverDatabaseMetaData(this, url);
  }

  @Override
  public DatabaseMetaData getMetaData() throws SQLException {
    requireOpen();
    return metadata;
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
  public PreparedStatement prepareStatement(String sql) throws SQLException {
    return prepareStatement(sql, Statement.NO_GENERATED_KEYS);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int generatedKeys)
      throws SQLException {
    requireOpen();
    if (generatedKeys != Statement.NO_GENERATED_KEYS
        && generatedKeys != Statement.RETURN_GENERATED_KEYS) {
      throw JdbcExceptions.unsupported();
    }
    if (statement != null) {
      throw JdbcExceptions.failure(StatusCode.CONFLICT, "prepare statement");
    }
    statement = new RiverJdbcPreparedStatement(
        this, session, sql, generatedKeys == Statement.RETURN_GENERATED_KEYS);
    return (PreparedStatement) statement;
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
  public Savepoint setSavepoint() throws SQLException {
    return createSavepoint(null);
  }

  @Override
  public Savepoint setSavepoint(String name) throws SQLException {
    if (!validSavepointName(name)) {
      throw JdbcExceptions.invalid("savepoint name is outside the bounded domain");
    }
    return createSavepoint(name);
  }

  @Override
  public void rollback(Savepoint target) throws SQLException {
    int savepoint = requireSavepoint(target, "rollback to savepoint");
    RiverJdbcSavepoint owned = savepoints[savepoint];
    executeTransactionCommand(
        "ROLLBACK TO SAVEPOINT " + owned.sqlName(),
        "rollback to savepoint");
    completeSavepointsFrom(savepoint + 1);
  }

  @Override
  public void releaseSavepoint(Savepoint target) throws SQLException {
    int savepoint = requireSavepoint(target, "release savepoint");
    RiverJdbcSavepoint owned = savepoints[savepoint];
    executeTransactionCommand(
        "RELEASE SAVEPOINT " + owned.sqlName(),
        "release savepoint");
    completeSavepointsFrom(savepoint);
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    SQLException closeFailure = null;
    AbstractResultSet currentMetadata = metadataResult;
    if (currentMetadata != null) {
      try {
        currentMetadata.close();
      } catch (SQLException failure) {
        closeFailure = failure;
      }
    }
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException failure) {
        if (closeFailure == null) {
          closeFailure = failure;
        }
      }
    }
    StatusCode sessionStatus = session.close();
    StatusCode connectionStatus = client.close();
    completeSavepointsFrom(0);
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
    if (level != Connection.TRANSACTION_READ_COMMITTED
        && level != Connection.TRANSACTION_REPEATABLE_READ
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
      String begin = isolation == Connection.TRANSACTION_READ_COMMITTED
          ? "BEGIN READ COMMITTED"
          : isolation == Connection.TRANSACTION_SERIALIZABLE
              ? "BEGIN SERIALIZABLE" : "BEGIN";
      transactionResult.reset();
      JdbcExceptions.require(session.execute(begin, transactionResult), "begin transaction");
      transactionActive = true;
    }
  }

  void commandCompleted(CommandResult result) {
    transactionActive = result.transactionActive();
    if (!transactionActive) {
      completeSavepointsFrom(0);
    }
  }

  void statementClosed(RiverJdbcStatement closedStatement) {
    if (statement == closedStatement) {
      statement = null;
    }
  }

  ResultSet openTables(
      String tableNamePattern,
      boolean includeTables,
      boolean includeViews,
      boolean scanCatalog) throws SQLException {
    requireOpen();
    requireMetadataResultAvailable();
    RiverQuery query = null;
    if (scanCatalog && (includeTables || includeViews)) {
      beforeExecution();
      metadataQuery.reset();
      JdbcExceptions.require(
          session.beginQuery("SHOW TABLES", metadataQuery),
          "read table metadata");
      query = metadataQuery.query();
    }
    metadataResult = RiverCatalogResultSet.tables(
        this,
        query,
        tableNamePattern,
        includeTables,
        includeViews);
    return metadataResult;
  }

  ResultSet openTableTypes() throws SQLException {
    requireOpen();
    requireMetadataResultAvailable();
    metadataResult = RiverCatalogResultSet.tableTypes(this);
    return metadataResult;
  }

  ResultSet openColumns(
      String tableNamePattern,
      String columnNamePattern,
      boolean scanCatalog) throws SQLException {
    requireOpen();
    requireMetadataResultAvailable();
    RiverQuery query = null;
    if (scanCatalog) {
      query = openMetadataQuery("SHOW TABLES", "read column catalog");
    }
    metadataResult = new RiverColumnsResultSet(
        this,
        query,
        tableNamePattern,
        columnNamePattern);
    return metadataResult;
  }

  ResultSet openPrimaryKeys(String tableName, boolean scanCatalog)
      throws SQLException {
    requireOpen();
    requireMetadataResultAvailable();
    RiverQuery query = null;
    if (scanCatalog) {
      query = openMetadataQuery("SHOW TABLES", "read primary-key catalog");
    }
    metadataResult = new RiverPrimaryKeyResultSet(this, query, tableName);
    return metadataResult;
  }

  ResultSet openIndexInfo(
      String tableName,
      boolean uniqueOnly,
      boolean scanCatalog) throws SQLException {
    requireOpen();
    requireMetadataResultAvailable();
    RiverQuery query = null;
    if (scanCatalog) {
      query = openMetadataQuery("SHOW TABLES", "read index catalog");
    }
    metadataResult = new RiverIndexInfoResultSet(
        this,
        query,
        tableName,
        uniqueOnly);
    return metadataResult;
  }

  RiverQuery openColumnDescription(String tableName) throws SQLException {
    return openMetadataQuery(
        "SELECT * FROM " + tableName + " LIMIT 1",
        "describe catalog table");
  }

  RiverQuery openIndexDescription(String tableName) throws SQLException {
    return openMetadataQuery(
        "SHOW INDEXES FROM " + tableName,
        "describe table indexes");
  }

  void metadataQueryCompleted(
      AbstractResultSet completed,
      CommandResult result) {
    metadataQueryClosed(result);
    metadataResultClosed(completed);
  }

  void metadataQueryClosed(CommandResult result) {
    commandCompleted(result);
  }

  void metadataResultClosed(AbstractResultSet completed) {
    if (metadataResult == completed) {
      metadataResult = null;
    }
  }

  private void requireMetadataResultAvailable() throws SQLException {
    if (metadataResult != null) {
      throw JdbcExceptions.failure(
          StatusCode.CONFLICT,
          "open catalog metadata result");
    }
  }

  private RiverQuery openMetadataQuery(String sql, String operation)
      throws SQLException {
    beforeExecution();
    metadataQuery.reset();
    JdbcExceptions.require(session.beginQuery(sql, metadataQuery), operation);
    return metadataQuery.query();
  }

  private void finishTransaction(String sql, String operation) throws SQLException {
    transactionResult.reset();
    JdbcExceptions.require(session.execute(sql, transactionResult), operation);
    transactionActive = transactionResult.transactionActive();
    completeSavepointsFrom(0);
  }

  private Savepoint createSavepoint(String name) throws SQLException {
    requireManualTransaction("create savepoint");
    if (savepointCount >= savepoints.length) {
      throw JdbcExceptions.failure(
          StatusCode.RESOURCE_EXHAUSTED,
          "create savepoint");
    }
    if (nextSavepointId <= 0) {
      throw JdbcExceptions.failure(
          StatusCode.RESOURCE_EXHAUSTED,
          "create savepoint");
    }
    beforeExecution();
    int id = nextSavepointId++;
    String sqlName = "jdbc_savepoint_" + id;
    executeTransactionCommand("SAVEPOINT " + sqlName, "create savepoint");
    RiverJdbcSavepoint created = new RiverJdbcSavepoint(
        this, id, name, sqlName);
    savepoints[savepointCount++] = created;
    return created;
  }

  private int requireSavepoint(
      Savepoint target,
      String operation) throws SQLException {
    requireManualTransaction(operation);
    if (!(target instanceof RiverJdbcSavepoint candidate)
        || !candidate.isOwnedBy(this)
        || !transactionActive) {
      throw JdbcExceptions.failure(StatusCode.CONFLICT, operation);
    }
    for (int index = savepointCount - 1; index >= 0; index--) {
      if (savepoints[index] == candidate) {
        return index;
      }
    }
    throw JdbcExceptions.failure(StatusCode.CONFLICT, operation);
  }

  private void executeTransactionCommand(String sql, String operation)
      throws SQLException {
    closeTransactionResult();
    transactionResult.reset();
    JdbcExceptions.require(session.execute(sql, transactionResult), operation);
    transactionActive = transactionResult.transactionActive();
  }

  private void completeSavepointsFrom(int first) {
    for (int index = savepointCount - 1; index >= first; index--) {
      savepoints[index].complete();
      savepoints[index] = null;
    }
    savepointCount = first;
  }

  private static boolean validSavepointName(String name) {
    if (name == null
        || name.isEmpty()
        || name.length() > MAXIMUM_SAVEPOINT_NAME_LENGTH) {
      return false;
    }
    for (int index = 0; index < name.length(); index++) {
      char character = name.charAt(index);
      if (character < 0x20 || character == 0x7f) {
        return false;
      }
    }
    return true;
  }

  private void closeTransactionResult() throws SQLException {
    AbstractResultSet currentMetadata = metadataResult;
    if (currentMetadata != null) {
      currentMetadata.close();
    }
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

  void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("connection");
    }
  }
}
