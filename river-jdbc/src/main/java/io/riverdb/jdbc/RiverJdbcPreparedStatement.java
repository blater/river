package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.RiverSession;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Prepared statement sending typed values separately from retained SQL text. */
final class RiverJdbcPreparedStatement extends RiverJdbcTypedPreparedStatement {
  static final int MAXIMUM_PARAMETERS = ParameterSet.MAXIMUM_PARAMETERS;

  private final RiverSession session;
  private final long handle;
  private final boolean query;
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
    this.session = session;
    PreparedOpenResult prepared = new PreparedOpenResult();
    JdbcExceptions.require(session.prepare(sql, prepared), "prepare statement");
    int localParameters = parameterCount(sql);
    if (prepared.parameterCount() != localParameters) {
      session.closePrepared(prepared.handle());
      throw JdbcExceptions.invalid("prepared parameter metadata mismatch");
    }
    handle = prepared.handle();
    query = prepared.query();
    returnGeneratedKeys = generatedKeys;
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    if (!query) throw JdbcExceptions.invalid("update handle must use executeUpdate");
    return executePreparedQuery(handle, boundParameters());
  }

  @Override
  public int executeUpdate() throws SQLException {
    if (query) throw JdbcExceptions.invalid("query handle must use executeQuery");
    return executePreparedUpdate(handle, boundParameters(), returnGeneratedKeys);
  }

  @Override
  public boolean execute() throws SQLException {
    if (query) {
      if (returnGeneratedKeys) throw JdbcExceptions.invalid("generated keys require update SQL");
      executePreparedQuery(handle, boundParameters());
      return true;
    }
    executePreparedUpdate(handle, boundParameters(), returnGeneratedKeys);
    return false;
  }

  @Override
  public void addBatch() throws SQLException {
    if (query) throw JdbcExceptions.invalid("query handle cannot be batched");
    addPreparedBatch(snapshotParameters());
  }

  @Override
  public int[] executeBatch() throws SQLException {
    requireOpen();
    closeOpenResult();
    return RiverJdbcBatchExecutor.executePrepared(this, handle);
  }

  @Override
  StatusCode releaseRetainedPrepared() {
    return session.closePrepared(handle);
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
    int count = RiverJdbcParameterTypes.countMarkers(sql);
    if (count < 0 || count > MAXIMUM_PARAMETERS) {
      throw JdbcExceptions.invalid("prepared SQL has invalid parameter markers");
    }
    return count;
  }
}
