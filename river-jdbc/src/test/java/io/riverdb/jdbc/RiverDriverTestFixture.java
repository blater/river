package io.riverdb.jdbc;

import static io.riverdb.jdbc.JdbcTestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.server.CredentialValidityFence;
import io.riverdb.server.CredentialValidityFenceOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.LoopbackServerOpenResult;
import io.riverdb.testsupport.TestTlsContexts;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/** Owns the embedded authenticated server and database lifetime for JDBC suites. */
final class RiverDriverTestFixture implements AutoCloseable {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a44424344524956L, 0x4552544553543031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  private final Path root;
  private RiverDatabase database;
  private LoopbackRiverServer server;
  private Path clientFile;

  private RiverDriverTestFixture(Path root, RiverDatabase database) {
    this.root = root;
    this.database = database;
  }

  static RiverDriverTestFixture open(Path root, int maximumOwners) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(
            databaseRequest(maximumOwners), root, DATABASE, GENERATION, maximumOwners, opened));
    RiverDriverTestFixture fixture = new RiverDriverTestFixture(root, opened.database());
    fixture.startServer();
    return fixture;
  }

  String url() {
    if (clientFile == null) throw new AssertionError("missing generated client file");
    return RiverDriver.CLIENT_FILE_PREFIX + clientFile;
  }

  void reopen() {
    close();
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(
            databaseRequest(8),
            root,
            DATABASE,
            GENERATION,
            8,
            io.riverdb.engine.EmbeddedLockDiagnosticsConfig.disabled(),
            opened));
    database = opened.database();
    startServer();
  }

  @Override
  public void close() {
    if (server != null) {
      assertEquals(StatusCode.OK, server.close());
      server = null;
    }
    if (database != null) {
      assertEquals(StatusCode.OK, database.close());
      database = null;
    }
  }

  private void startServer() {
    try {
      byte[] token = new byte[32];
      java.util.Arrays.fill(token, (byte) '-');
      TokenAuthenticatorOpenResult authenticator = new TokenAuthenticatorOpenResult();
      assertEquals(StatusCode.OK, TokenAuthenticator.create(
          token, token.length, authenticator));
      LoopbackServerOpenResult result = new LoopbackServerOpenResult();
      assertEquals(StatusCode.OK, LoopbackRiverServer.startAuthenticated(
          database,
          InetAddress.getLoopbackAddress(),
          0,
          TestTlsContexts.server(),
          authenticator.authenticator(),
          validityFence(),
          LoopbackServerLimits.defaults(8),
          result));
      server = result.server();
      clientFile = TestTlsContexts.writeClientProperties(
          Files.createTempDirectory(root, "driver-client-"),
          DATABASE,
          1,
          server.port(),
          token);
      java.util.Arrays.fill(token, (byte) 0);
    } catch (Exception failure) {
      throw new AssertionError("open authenticated JDBC fixture", failure);
    }
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
