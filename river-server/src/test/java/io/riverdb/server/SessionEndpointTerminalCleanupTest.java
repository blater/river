package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class SessionEndpointTerminalCleanupTest {
  @Test
  void disconnectTransfersRetryableCloseExactlyOnce() {
    RetryDatabase database = new RetryDatabase();
    SessionEndpoint endpoint = new SessionEndpoint(database);
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    ByteBuffer response = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK,
        codec.encodeRequest(request, ProtocolMessageType.HELLO, 1));
    assertEquals(StatusCode.OK, endpoint.process(request, response));
    assertEquals(StatusCode.OK,
        codec.encodeRequest(request, ProtocolMessageType.OPEN_SESSION, 2));
    assertEquals(StatusCode.OK, endpoint.process(request, response));

    assertEquals(StatusCode.RETRY, ServerTerminalSessionCleanup.complete(endpoint));
    assertTrue(endpoint.isClosed());
    assertEquals(1, database.session.closeAttempts);
    assertEquals(1, database.transfers);
    assertEquals(StatusCode.CLOSED, ServerTerminalSessionCleanup.complete(endpoint));
    assertEquals(1, database.session.closeAttempts);
    assertEquals(1, database.transfers);
  }

  private static final class RetryDatabase implements RiverDatabase {
    private final RetrySession session = new RetrySession();
    private int transfers;

    @Override
    public StatusCode createSession(SessionOpenResult result) {
      return result.complete(session);
    }

    @Override
    public StatusCode deferTerminalClose(RiverSession transferred) {
      if (transferred != session || transfers != 0) return StatusCode.NOT_OWNER;
      transfers++;
      return StatusCode.OK;
    }

    @Override
    public StatusCode close() { return StatusCode.OK; }
  }

  private static final class RetrySession implements RiverSession {
    private int closeAttempts;

    @Override
    public StatusCode close() { closeAttempts++; return StatusCode.RETRY; }
    @Override
    public StatusCode prepare(String sql, PreparedOpenResult result) { return StatusCode.CLOSED; }
    @Override
    public StatusCode executePrepared(long handle, ParameterSet parameters, CommandResult result) {
      return StatusCode.CLOSED;
    }
    @Override
    public StatusCode beginPreparedQuery(
        long handle, ParameterSet parameters, QueryOpenResult result) {
      return StatusCode.CLOSED;
    }
    @Override
    public StatusCode closePrepared(long handle) { return StatusCode.CLOSED; }
    @Override
    public StatusCode prepareProgram(TransactionProgram program, ProgramOpenResult result) {
      return StatusCode.CLOSED;
    }
    @Override
    public StatusCode executeProgram(long handle, TransactionProgramArguments arguments,
        TransactionProgramResult result) { return StatusCode.CLOSED; }
    @Override
    public StatusCode closeProgram(long handle) { return StatusCode.CLOSED; }
    @Override
    public StatusCode execute(String sql, CommandResult result) { return StatusCode.CLOSED; }
    @Override
    public StatusCode execute(String sql, ParameterSet parameters, CommandResult result) {
      return StatusCode.CLOSED;
    }
    @Override
    public StatusCode beginQuery(String sql, QueryOpenResult result) { return StatusCode.CLOSED; }
    @Override
    public StatusCode beginQuery(String sql, ParameterSet parameters, QueryOpenResult result) {
      return StatusCode.CLOSED;
    }
  }
}
