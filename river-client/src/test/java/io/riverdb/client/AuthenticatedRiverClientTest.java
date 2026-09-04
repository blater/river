package io.riverdb.client;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.LoopbackServerOpenResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AuthenticatedRiverClientTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x544c534155544844L, 0x4154414241534531L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void authenticatesInsideTlsThenExecutesAndReopens(@TempDir Path root)
      throws Exception {
    byte[] token = "river-integration-auth-token".getBytes(StandardCharsets.UTF_8);
    byte[] wrongToken = "wrong-integration-auth-token".getBytes(StandardCharsets.UTF_8);
    TokenAuthenticatorOpenResult authResult = new TokenAuthenticatorOpenResult();
    assertEquals(
        StatusCode.OK,
        TokenAuthenticator.create(token, token.length, authResult));
    SSLContext serverContext = TestTlsContexts.server();
    SSLContext clientContext = TestTlsContexts.trustedClient();
    DatabaseOpenResult engineResult = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(
            databaseRequest(4), root, DATABASE, GENERATION, 4, engineResult));
    RiverDatabase engine = engineResult.database();
    LoopbackRiverServer wrongHostnameServer = start(
        engine,
        root,
        TestTlsContexts.wrongHostnameServer(),
        authResult.authenticator());
    RiverClientOpenResult clientResult = new RiverClientOpenResult();
    assertEquals(
        StatusCode.IO_FAILURE,
        RiverClientConnection.connectAuthenticatedLoopback(
            wrongHostnameServer.port(),
            TestTlsContexts.wrongHostnameClient(),
            token,
            token.length,
            clientResult));
    assertEquals(StatusCode.OK, wrongHostnameServer.close());

    LoopbackRiverServer server = start(
        engine,
        root,
        serverContext,
        authResult.authenticator());
    assertTrue(server.isAuthenticatedTransport());

    assertEquals(
        StatusCode.IO_FAILURE,
        RiverClientConnection.connectAuthenticatedLoopback(
            server.port(),
            SSLContext.getDefault(),
            token,
            token.length,
            clientResult));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RiverClientConnection.connectAuthenticatedLoopback(
            server.port(),
            clientContext,
            wrongToken,
            wrongToken.length,
            clientResult));
    assertEquals(
        StatusCode.OK,
        RiverClientConnection.connectAuthenticatedLoopback(
            server.port(), clientContext, token, token.length, clientResult));
    RiverClientConnection client = clientResult.connection();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE secure_accounts "
                + "(id BIGINT PRIMARY KEY, balance BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO secure_accounts VALUES (1, 750)",
            command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertTrue(server.authenticationFailures() >= 1);
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, engine.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(
            databaseRequest(4), root, DATABASE, GENERATION, 4, engineResult));
    engine = engineResult.database();
    server = start(engine, root, serverContext, authResult.authenticator());
    assertEquals(
        StatusCode.OK,
        RiverClientConnection.connectAuthenticatedLoopback(
            server.port(), clientContext, token, token.length, clientResult));
    client = clientResult.connection();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT balance FROM secure_accounts WHERE id=1",
            command));
    assertTrue(command.rowAvailable());
    assertEquals(750, command.valueAt(0));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, engine.close());
    Arrays.fill(token, (byte) 0);
    Arrays.fill(wrongToken, (byte) 0);
  }

  @Test
  void idleAuthenticatedClientReleasesItsTransactionAndSlot(
      @TempDir Path root) throws Exception {
    byte[] token = "river-idle-auth-token-0001".getBytes(StandardCharsets.UTF_8);
    TokenAuthenticatorOpenResult authResult = new TokenAuthenticatorOpenResult();
    assertEquals(
        StatusCode.OK,
        TokenAuthenticator.create(
            token,
            token.length,
            11,
            SessionPermissions.ALL,
            authResult));
    DatabaseOpenResult engineResult = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(
            databaseRequest(4), root, DATABASE, GENERATION, 4, engineResult));
    RiverDatabase engine = engineResult.database();
    SessionOpenResult localResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, engine.createSession(localResult));
    CommandResult command = new CommandResult();
    assertEquals(
        StatusCode.OK,
        localResult.session().execute("CREATE TABLE idle_rows", command));
    assertEquals(StatusCode.OK, localResult.session().close());

    LoopbackServerOpenResult serverResult = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.startAuthenticated(
            engine,
            0,
            TestTlsContexts.server(),
            authResult.authenticator(),
            root,
            new LoopbackServerLimits(1, 5_000, 200, 64),
            serverResult));
    LoopbackRiverServer server = serverResult.server();
    RiverClientOpenResult clientResult = new RiverClientOpenResult();
    assertEquals(
        StatusCode.OK,
        RiverClientConnection.connectAuthenticatedLoopback(
            server.port(),
            TestTlsContexts.trustedClient(),
            token,
            token.length,
            clientResult));
    RiverClientConnection client = clientResult.connection();
    SessionOpenResult remoteResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(remoteResult));
    assertEquals(
        StatusCode.OK,
        remoteResult.session().execute("BEGIN", command));
    assertEquals(
        StatusCode.OK,
        remoteResult.session().execute(
            "INSERT INTO idle_rows VALUES (9, 900)", command));

    awaitConnections(server, 0);
    assertEquals(StatusCode.TIMEOUT, server.lastStatus());
    assertEquals(StatusCode.OK, engine.createSession(localResult));
    assertEquals(
        StatusCode.CONFLICT,
        localResult.session().execute(
            "SELECT value FROM idle_rows WHERE key=9", command));
    assertEquals(StatusCode.OK, localResult.session().close());
    assertEquals(StatusCode.OK, client.cancel());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, engine.close());
    Arrays.fill(token, (byte) 0);
  }

  private static LoopbackRiverServer start(
      RiverDatabase database,
      Path auditDirectory,
      SSLContext context,
      TokenAuthenticator authenticator) {
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.startAuthenticated(
            database,
            0,
            context,
            authenticator,
            auditDirectory,
            LoopbackServerLimits.defaults(
                LoopbackRiverServer.DEFAULT_MAXIMUM_CONNECTIONS),
            result));
    return result.server();
  }

  private static void awaitConnections(
      LoopbackRiverServer server,
      int expected) throws InterruptedException {
    long deadline = System.nanoTime() + 3_000_000_000L;
    while (server.activeConnections() != expected
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(expected, server.activeConnections());
  }
}
