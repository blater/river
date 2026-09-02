package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.client.RiverClientConnection;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.IsolationLevel;
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
import java.util.concurrent.Executor;

/** One JDBC connection owns one ordered remote River session. */
final class RiverJdbcConnection extends AbstractConnection implements RiverConnectionMetrics {
  private static final int MAXIMUM_SAVEPOINT_NAME_LENGTH = 64;
  private static final int MAXIMUM_SAVEPOINTS = 3;

  final RiverClientConnection client;
  final RiverSession session;
  private final RiverDatabaseMetaData metadata;
  private final CommandResult transactionResult = new CommandResult();
  private final RiverJdbcPrograms programs;
  private final QueryOpenResult metadataQuery = new QueryOpenResult();
  private final RiverJdbcSavepoint[] savepoints =
      new RiverJdbcSavepoint[MAXIMUM_SAVEPOINTS];
  final RiverJdbcStatementRegistry statements = new RiverJdbcStatementRegistry();
  AbstractResultSet metadataResult;
  private boolean autoCommit = true;
  private boolean transactionActive;
  volatile boolean closed;
  private int isolation = Connection.TRANSACTION_REPEATABLE_READ;
  private int nextSavepointId = 1;
  private int savepointCount;

  RiverJdbcConnection(
      RiverClientConnection remoteClient,
      RiverSession remoteSession,
      String url) {
    client = remoteClient;
    session = remoteSession;
    programs = new RiverJdbcPrograms(this, session);
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
    RiverJdbcStatement statement = new RiverJdbcStatement(this, session);
    try {
      statements.register(statement);
    } catch (SQLException failure) {
      statement.close();
      throw failure;
    }
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
    RiverJdbcPreparedStatement statement = new RiverJdbcPreparedStatement(
        this, session, sql, generatedKeys == Statement.RETURN_GENERATED_KEYS);
    try {
      statements.register(statement);
    } catch (SQLException failure) {
      statement.close();
      throw failure;
    }
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
    RiverJdbcConnectionCloser.close(this);
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void abort(Executor executor) throws SQLException {
    if (executor == null) {
      throw JdbcExceptions.invalid("abort executor must not be null");
    }
    if (closed) {
      return;
    }
    closed = true;
    transactionActive = false;
    completeSavepointsFrom(0);
    executor.execute(client::cancel);
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
    RiverJdbcIsolation.requireSupported(level);
    isolation = level;
  }

  @Override
  public int getTransactionIsolation() throws SQLException {
    requireOpen();
    return isolation;
  }

  IsolationLevel programIsolationLevel() {
    return RiverJdbcIsolation.toRiver(isolation);
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
    if (type != null && type.isInstance(programs)) return type.cast(programs);
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isWrapperFor(Class<?> type) {
    return type != null && (type.isInstance(this) || type.isInstance(programs));
  }

  @Override
  public long completedRequests() {
    return client.completedRequests();
  }

  @Override
  public long bytesSent() {
    return client.bytesSent();
  }

  @Override
  public long bytesReceived() {
    return client.bytesReceived();
  }

  void beforeExecution() throws SQLException {
    requireOpen();
    transactionActive = RiverJdbcTransactionStarter.ensure(
        session,
        transactionResult,
        autoCommit,
        transactionActive,
        isolation);
  }

  void commandCompleted(CommandResult result) {
    transactionActive = result.transactionActive();
    if (!transactionActive) {
      completeSavepointsFrom(0);
    }
  }

  void statementClosed(RiverJdbcStatement closedStatement) {
    statements.unregister(closedStatement);
  }

  void cancelCurrentOperation() throws SQLException {
    if (closed) {
      return;
    }
    StatusCode status = client.cancel();
    closed = true;
    transactionActive = false;
    completeSavepointsFrom(0);
    if (!status.isOk() && status != StatusCode.CLOSED) {
      throw JdbcExceptions.failure(status, "cancel statement");
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
    StatusCode status = session.execute(sql, transactionResult);
    transactionActive = transactionResult.transactionActive();
    if (!transactionActive) completeSavepointsFrom(0);
    if (status == StatusCode.CONFLICT && "ROLLBACK".equals(sql)) return;
    JdbcExceptions.require(status, operation);
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

  void completeSavepointsFrom(int first) {
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
    statements.closeOpenResults();
  }

  private void requireManualTransaction(String operation) throws SQLException {
    requireOpen();
    if (autoCommit) {
      throw JdbcExceptions.failure(StatusCode.CONFLICT, operation + " in auto-commit mode");
    }
  }

  void requireProgramBoundary(String operation) throws SQLException {
    requireOpen();
    if (transactionActive) {
      throw JdbcExceptions.failure(
          StatusCode.CONFLICT, operation + " requires an idle connection");
    }
    closeTransactionResult();
  }

  void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("connection");
    }
  }
}
