package io.riverdb.jdbc;

import io.riverdb.server.CredentialValidityFence;
import io.riverdb.server.CredentialValidityFenceOpenResult;
import static io.riverdb.jdbc.JdbcTestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.riverdb.testsupport.TestTlsContexts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SecureRemoteJdbcGateTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5345435552454a44L, 0x4243474154453031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void readRoleIsEnforcedAcrossServerRestart(
      @TempDir Path root) throws Exception {
    RiverDatabase database = createDatabase(root);
    seed(database);
    byte[] token = token("river-read-role-token-0001");
    TokenAuthenticator authenticator = authenticator(
        token, 42, SessionPermissions.READ);
    SSLContext serverContext = TestTlsContexts.server();
    LoopbackServerLimits limits = new LoopbackServerLimits(4, 5_000, 30_000);
    LoopbackRiverServer server = startAuthenticated(
        database, serverContext, authenticator, limits);

    byte[] wrongToken = token.clone();
    wrongToken[0] ^= 1;
    RiverDataSource rejected = source(root, server, wrongToken);
    SQLException authenticationFailure = assertThrows(
        SQLException.class,
        rejected::getConnection);
    assertEquals("28000", authenticationFailure.getSQLState());
    rejected.close();
    Arrays.fill(wrongToken, (byte) 0);

    RiverDataSource source = source(root, server, token);
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      try (ResultSet result = statement.executeQuery(
          "SELECT value FROM secure_rows WHERE id=1")) {
        assertTrue(result.next());
        assertEquals(700, result.getLong(1));
        assertFalse(result.next());
      }
      SQLException writeDenied = assertThrows(
          SQLException.class,
          () -> statement.executeUpdate(
              "INSERT INTO secure_rows VALUES (2, 800)"));
      assertEquals("42501", writeDenied.getSQLState());
      SQLException schemaDenied = assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("CREATE TABLE forbidden_table"));
      assertEquals("42501", schemaDenied.getSQLState());
      SQLException adminDenied = assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("CHECKPOINT"));
      assertEquals("42501", adminDenied.getSQLState());

      connection.setAutoCommit(false);
      try (ResultSet result = statement.executeQuery(
          "SELECT value FROM secure_rows WHERE id=1")) {
        assertTrue(result.next());
      }
      connection.rollback();
    }
    source.close();
    assertEquals(StatusCode.OK, server.close());
    assertTrue(server.authorizationFailures() >= 3);

    LoopbackRiverServer reopened = startAuthenticated(
        database, serverContext, authenticator, limits);
    RiverDataSource restartedSource = source(root, reopened, token);
    try (Connection connection = restartedSource.getConnection();
        Statement statement = connection.createStatement()) {
      try (ResultSet result = statement.executeQuery("SELECT value FROM secure_rows WHERE id=1")) {
        assertTrue(result.next());
        assertEquals(700, result.getLong(1));
      }
      SQLException denied = assertThrows(SQLException.class,
          () -> statement.executeUpdate("INSERT INTO secure_rows VALUES (2, 800)"));
      assertEquals("42501", denied.getSQLState());
    }
    restartedSource.close();
    assertEquals(StatusCode.OK, reopened.close());
    Arrays.fill(token, (byte) 0);
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void jdbcCancelClosesTransportAndRollsBackRemoteTransaction(
      @TempDir Path root) throws Exception {
    RiverDatabase database = createDatabase(root);
    seed(database);
    byte[] token = token("river-jdbc-cancel-token-01");
    TokenAuthenticator authenticator = authenticator(
        token, 19, SessionPermissions.ALL);
    LoopbackRiverServer server = startAuthenticated(
        database,
        TestTlsContexts.server(),
        authenticator,
        new LoopbackServerLimits(2, 5_000, 30_000));
    RiverDataSource source = source(root, server, token);

    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      assertEquals(
          1,
          statement.executeUpdate(
              "INSERT INTO secure_rows VALUES (9, 900)"));
      statement.cancel();
      assertTrue(connection.isClosed());
    }
    source.close();
    awaitConnections(server, 0);

    SessionOpenResult opened = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    CommandResult result = new CommandResult();
    assertEquals(
        StatusCode.CONFLICT,
        opened.session().execute(
            "SELECT value FROM secure_rows WHERE id=9", result));
    assertEquals(StatusCode.OK, opened.session().close());
    assertEquals(StatusCode.OK, server.close());
    Arrays.fill(token, (byte) 0);
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void jdbcAbortClosesTransportAndRollsBackRemoteTransaction(
      @TempDir Path root) throws Exception {
    RiverDatabase database = createDatabase(root);
    seed(database);
    byte[] token = token("river-jdbc-abort-token-001");
    TokenAuthenticator authenticator = authenticator(
        token, 20, SessionPermissions.ALL);
    LoopbackRiverServer server = startAuthenticated(
        database,
        TestTlsContexts.server(),
        authenticator,
        new LoopbackServerLimits(2, 5_000, 30_000));
    RiverDataSource source = source(root, server, token);

    Connection connection = source.getConnection();
    try (Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      assertEquals(
          1,
          statement.executeUpdate(
              "INSERT INTO secure_rows VALUES (10, 1000)"));
      connection.abort(Runnable::run);
      assertTrue(connection.isClosed());
    }
    source.close();
    awaitConnections(server, 0);

    SessionOpenResult opened = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    CommandResult result = new CommandResult();
    assertEquals(
        StatusCode.CONFLICT,
        opened.session().execute(
            "SELECT value FROM secure_rows WHERE id=10", result));
    assertEquals(StatusCode.OK, opened.session().close());
    assertEquals(StatusCode.OK, server.close());
    Arrays.fill(token, (byte) 0);
    assertEquals(StatusCode.OK, database.close());
  }

  private static RiverDatabase createDatabase(Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static void seed(RiverDatabase database) {
    SessionOpenResult opened = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    RiverSession session = opened.session();
    CommandResult result = new CommandResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE secure_rows (id BIGINT PRIMARY KEY, value BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO secure_rows VALUES (1, 700)", result));
    assertEquals(StatusCode.OK, session.close());
  }

  private static TokenAuthenticator authenticator(
      byte[] token,
      long principalId,
      int permissions) {
    TokenAuthenticatorOpenResult opened = new TokenAuthenticatorOpenResult();
    assertEquals(
        StatusCode.OK,
        TokenAuthenticator.create(
            token, token.length, principalId, permissions, opened));
    return opened.authenticator();
  }

  private static LoopbackRiverServer startAuthenticated(
      RiverDatabase database,
      SSLContext context,
      TokenAuthenticator authenticator,
      LoopbackServerLimits limits) throws Exception {
    LoopbackServerOpenResult opened = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.startAuthenticated(
            database,
            java.net.InetAddress.getLoopbackAddress(),
            0,
            context,
            authenticator,
            validityFence(),
            limits,
            opened));
    return opened.server();
  }

  private static RiverDataSource source(
      Path root,
      LoopbackRiverServer server,
      byte[] token) throws Exception {
    Path clientFile = TestTlsContexts.writeClientProperties(
        Files.createTempDirectory(root, "client-"), DATABASE, 1, server.port(), token);
    RiverDataSource source = new RiverDataSource();
    source.setClientFile(clientFile);
    return source;
  }

  private static byte[] token(String value) {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length > 32) throw new IllegalArgumentException("test token too long");
    byte[] token = new byte[32];
    Arrays.fill(token, (byte) '-');
    System.arraycopy(encoded, 0, token, 0, encoded.length);
    return token;
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
  private static CredentialValidityFence validityFence() {
    CredentialValidityFenceOpenResult opened = new CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    if (CredentialValidityFence.create(now - 300_000L, now + 86_400_000L, opened)
        != StatusCode.OK) {
      throw new AssertionError("test credential validity bounds");
    }
    return opened.fence();
  }

}
