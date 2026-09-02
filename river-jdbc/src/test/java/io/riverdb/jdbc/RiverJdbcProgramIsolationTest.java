package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

final class RiverJdbcProgramIsolationTest {
  @Test
  void programsUseOwningConnectionIsolation() throws SQLException {
    CapturingSession session = new CapturingSession();
    RiverJdbcConnection connection = new RiverJdbcConnection(
        null, session, "jdbc:river://localhost:1");
    RiverTransactionPrograms programs = connection.unwrap(RiverTransactionPrograms.class);
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    TransactionProgramResult result = new TransactionProgramResult();

    programs.executeProgram(1, arguments, result);
    assertEquals(IsolationLevel.REPEATABLE_READ, session.lastIsolationLevel);

    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    programs.executeProgram(1, arguments, result);
    assertEquals(IsolationLevel.READ_COMMITTED, session.lastIsolationLevel);

    connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    programs.executeProgram(1, arguments, result);
    assertEquals(IsolationLevel.SERIALIZABLE, session.lastIsolationLevel);
  }

  private static final class CapturingSession implements RiverSession {
    private IsolationLevel lastIsolationLevel;

    @Override
    public StatusCode executeProgram(
        long handle,
        IsolationLevel isolationLevel,
        TransactionProgramArguments arguments,
        TransactionProgramResult result) {
      lastIsolationLevel = isolationLevel;
      result.complete(1);
      return StatusCode.OK;
    }

    @Override
    public StatusCode close() {
      return StatusCode.OK;
    }

    @Override
    public StatusCode prepare(String sql, PreparedOpenResult result) {
      return StatusCode.CLOSED;
    }

    @Override
    public StatusCode executePrepared(
        long handle, ParameterSet parameters, CommandResult result) {
      return StatusCode.CLOSED;
    }

    @Override
    public StatusCode beginPreparedQuery(
        long handle, ParameterSet parameters, QueryOpenResult result) {
      return StatusCode.CLOSED;
    }

    @Override
    public StatusCode closePrepared(long handle) {
      return StatusCode.CLOSED;
    }

    @Override
    public StatusCode prepareProgram(
        TransactionProgram program, ProgramOpenResult result) {
      return StatusCode.CLOSED;
    }

    @Override
    public StatusCode closeProgram(long handle) {
      return StatusCode.CLOSED;
    }

    @Override
    public StatusCode execute(String sql, CommandResult result) {
      return StatusCode.CLOSED;
    }

    @Override
    public StatusCode execute(
        String sql, ParameterSet parameters, CommandResult result) {
      return StatusCode.CLOSED;
    }

    @Override
    public StatusCode beginQuery(String sql, QueryOpenResult result) {
      return StatusCode.CLOSED;
    }

    @Override
    public StatusCode beginQuery(
        String sql, ParameterSet parameters, QueryOpenResult result) {
      return StatusCode.CLOSED;
    }
  }
}
