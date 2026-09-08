package io.riverdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.LoopbackServerOpenResult;
import io.riverdb.server.SecurityAuditLog;
import io.riverdb.server.SecurityAuditLogFactory;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.testsupport.SecurityAuditTestOwner;
import io.riverdb.testsupport.TestTlsContexts;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverClientProgramIsolationTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(91, 93);
  private static final byte[] TOKEN =
      "river-client-program-isolation-token".getBytes(StandardCharsets.UTF_8);

  @Test
  void carriesEveryProgramIsolationThroughClientAndServer(@TempDir Path root) throws Exception {
    CapturingDatabase database = new CapturingDatabase();
    TokenAuthenticatorOpenResult authenticator = new TokenAuthenticatorOpenResult();
    assertEquals(StatusCode.OK, TokenAuthenticator.create(TOKEN, TOKEN.length, authenticator));
    io.riverdb.server.CredentialValidityFenceOpenResult fence =
        new io.riverdb.server.CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    assertEquals(
        StatusCode.OK,
        io.riverdb.server.CredentialValidityFence.create(now - 1_000L, now + 60_000L, fence));
    SecurityAuditLog audit = SecurityAuditTestOwner.create(
        root, DATABASE, 1,
        SecurityAuditLogFactory.DEFAULT_ACTIVE_MAXIMUM_BYTES,
        SecurityAuditLogFactory.DEFAULT_PENDING_MAXIMUM_BYTES);
    LoopbackServerOpenResult serverResult = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.startAuthenticated(
            database,
            InetAddress.getLoopbackAddress(),
            0,
            TestTlsContexts.server(),
            authenticator.authenticator(),
            audit,
            fence.fence(),
            LoopbackServerLimits.defaults(2),
            serverResult));
    LoopbackRiverServer server = serverResult.server();
    RiverClientOpenResult clientResult = new RiverClientOpenResult();
    assertEquals(
        StatusCode.OK,
        RiverClientConnection.connectAuthenticatedLoopback(
            server.port(), TestTlsContexts.trustedClient(), TOKEN, TOKEN.length, clientResult));
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
    public StatusCode createSession(SessionAuthorizer authorizer, SessionOpenResult result) {
      return authorizer == null ? StatusCode.INVALID_EXTERNAL_INPUT : result.complete(session);
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
