package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import java.sql.SQLException;

/** JDBC adapter for the session-owned transaction-program lifecycle. */
final class RiverJdbcPrograms implements RiverTransactionPrograms {
  private final RiverJdbcConnection connection;
  private final RiverSession session;
  private final ProgramOpenResult opened = new ProgramOpenResult();
  private final PreparedOpenResult prepared = new PreparedOpenResult();

  RiverJdbcPrograms(RiverJdbcConnection owner, RiverSession remoteSession) {
    connection = owner;
    session = remoteSession;
  }

  @Override
  public long prepareStatement(String sql) throws SQLException {
    connection.requireProgramBoundary("prepare program statement");
    prepared.reset();
    StatusCode status = session.prepare(sql, prepared);
    if (!status.isOk()) throw JdbcExceptions.failure(status, "prepare program statement");
    return prepared.handle();
  }

  @Override
  public void closeStatement(long handle) throws SQLException {
    connection.requireProgramBoundary("close program statement");
    StatusCode status = session.closePrepared(handle);
    if (!status.isOk()) throw JdbcExceptions.failure(status, "close program statement");
  }

  @Override
  public long prepareProgram(TransactionProgram program) throws SQLException {
    connection.requireProgramBoundary("prepare transaction program");
    opened.reset();
    StatusCode status = session.prepareProgram(program, opened);
    if (!status.isOk()) throw JdbcExceptions.failure(status, "prepare transaction program");
    return opened.handle();
  }

  @Override
  public void executeProgram(
      long handle,
      TransactionProgramArguments arguments,
      TransactionProgramResult result) throws SQLException {
    connection.requireProgramBoundary("execute transaction program");
    StatusCode status = session.executeProgram(
        handle, connection.programIsolationLevel(), arguments, result);
    if (!status.isOk()) throw JdbcExceptions.failure(status, "execute transaction program");
  }

  @Override
  public void closeProgram(long handle) throws SQLException {
    connection.requireProgramBoundary("close transaction program");
    StatusCode status = session.closeProgram(handle);
    if (!status.isOk()) throw JdbcExceptions.failure(status, "close transaction program");
  }
}
