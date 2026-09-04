package io.riverdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import org.junit.jupiter.api.Test;

final class RiverClientProgramIsolationTest {
  @Test
  void carriesEveryProgramIsolationThroughClientAndServer() {
    CapturingDatabase database = new CapturingDatabase();
    LoopbackServerOpenResult serverResult = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.start(database, 0, serverResult));
    LoopbackRiverServer server = serverResult.server();
    RiverClientOpenResult clientResult = new RiverClientOpenResult();
    assertEquals(
        StatusCode.OK,
        RiverClientConnection.connectLoopback(server.port(), clientResult));
    RiverClientConnection client = clientResult.connection();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    TransactionProgram program = new TransactionProgram();
    assertEquals(
        StatusCode.OK,
        program.beginStep(1, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    ProgramOpenResult opened = new ProgramOpenResult();
    assertEquals(StatusCode.OK, session.prepareProgram(program, opened));

    for (IsolationLevel isolationLevel : IsolationLevel.values()) {
      TransactionProgramResult result = new TransactionProgramResult();
      assertEquals(
          StatusCode.OK,
          session.executeProgram(
              opened.handle(), isolationLevel,
              new TransactionProgramArguments(), result));
      assertEquals(isolationLevel, database.session.lastIsolationLevel);
      assertEquals(1, result.commitSequence());
    }

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
  }

  private static final class CapturingDatabase implements RiverDatabase {
    private final CapturingSession session = new CapturingSession();

    @Override
    public StatusCode createSession(SessionOpenResult result) {
      return result.complete(session);
    }

    @Override
    public StatusCode deferTerminalClose(RiverSession unreachable) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    @Override
    public StatusCode close() {
      return StatusCode.OK;
    }
  }

  private static final class CapturingSession implements RiverSession {
    private volatile IsolationLevel lastIsolationLevel;

    @Override
    public StatusCode configureTransactionDiagnostics(
        long diagnosticTag, long diagnosticStepTag, long metricsEpoch) {
      return StatusCode.OK;
    }

    @Override
    public StatusCode prepareProgram(
        TransactionProgram program, ProgramOpenResult result) {
      return result.complete(7, 0);
    }

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
    public StatusCode closeProgram(long handle) {
      return StatusCode.OK;
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
