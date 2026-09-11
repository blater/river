package io.riverdb.jdbc;

import static io.riverdb.jdbc.JdbcTestDatabaseResources.databaseRequest;

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
import java.util.Arrays;

/** Owns the embedded authenticated server and database lifetime for JDBC suites. */
final class RiverDriverTestFixture implements AutoCloseable {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a44424344524956L, 0x4552544553543031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  private final Path root;
  private final int maximumOwners;
  private RiverDatabase database;
  private LoopbackRiverServer server;
  private Path clientFile;

  private RiverDriverTestFixture(Path root, int maximumOwners, RiverDatabase database) {
    this.root = root;
    this.maximumOwners = maximumOwners;
    this.database = database;
  }

  static RiverDriverTestFixture open(Path root, int maximumOwners) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    StatusCode status;
    try {
      status = EmbeddedRiver.create(
          databaseRequest(maximumOwners), root, DATABASE, GENERATION, maximumOwners, opened);
    } catch (Throwable failure) {
      closeDatabase(opened.database(), failure);
      rethrow(failure);
      throw new AssertionError("unreachable");
    }
    if (status != StatusCode.OK) {
      Throwable failure = statusFailure("create JDBC fixture", status);
      closeDatabase(opened.database(), failure);
      rethrow(failure);
    }
    RiverDriverTestFixture fixture =
        new RiverDriverTestFixture(root, maximumOwners, opened.database());
    try {
      fixture.startServer();
      return fixture;
    } catch (Throwable failure) {
      fixture.closeAfterFailure(failure);
      rethrow(failure);
      throw new AssertionError("unreachable");
    }
  }

  String url() {
    if (clientFile == null) throw new AssertionError("missing generated client file");
    return RiverDriver.CLIENT_FILE_PREFIX + clientFile;
  }

  RiverDatabase database() {
    if (database == null) throw new AssertionError("closed JDBC fixture");
    return database;
  }

  void reopen() {
    close();
    DatabaseOpenResult opened = new DatabaseOpenResult();
    StatusCode status;
    try {
      status = EmbeddedRiver.openExisting(
          databaseRequest(maximumOwners),
          root,
          DATABASE,
          GENERATION,
          maximumOwners,
          io.riverdb.engine.EmbeddedLockDiagnosticsConfig.disabled(),
          opened);
    } catch (Throwable failure) {
      closeDatabase(opened.database(), failure);
      rethrow(failure);
      throw new AssertionError("unreachable");
    }
    if (status != StatusCode.OK) {
      Throwable failure = statusFailure("reopen JDBC fixture", status);
      closeDatabase(opened.database(), failure);
      rethrow(failure);
    }
    database = opened.database();
    try {
      startServer();
    } catch (Throwable failure) {
      closeAfterFailure(failure);
      rethrow(failure);
    }
  }

  @Override
  public void close() {
    Throwable failure = closeResources();
    if (failure != null) throw new AssertionError("close JDBC fixture", failure);
  }

  private void closeAfterFailure(Throwable primary) {
    Throwable cleanup = closeResources();
    if (cleanup != null) primary.addSuppressed(cleanup);
  }

  private Throwable closeResources() {
    LoopbackRiverServer ownedServer = server;
    RiverDatabase ownedDatabase = database;
    server = null;
    database = null;
    clientFile = null;
    Throwable failure = null;
    if (ownedServer != null) {
      try {
        requireStatus("close JDBC server", ownedServer.close());
      } catch (Throwable closeFailure) {
        failure = closeFailure;
      }
    }
    if (ownedDatabase != null) {
      try {
        requireStatus("close JDBC database", ownedDatabase.close());
      } catch (Throwable closeFailure) {
        if (failure == null) failure = closeFailure;
        else failure.addSuppressed(closeFailure);
      }
    }
    return failure;
  }

  private void startServer() {
    byte[] token = new byte[32];
    Arrays.fill(token, (byte) '-');
    try {
      TokenAuthenticatorOpenResult authenticator = new TokenAuthenticatorOpenResult();
      requireStatus("create JDBC authenticator", TokenAuthenticator.create(
          token, token.length, authenticator));
      LoopbackServerOpenResult result = new LoopbackServerOpenResult();
      StatusCode status = LoopbackRiverServer.startAuthenticated(
          database,
          InetAddress.getLoopbackAddress(),
          0,
          TestTlsContexts.server(),
          authenticator.authenticator(),
          validityFence(),
          LoopbackServerLimits.defaults(8),
          result);
      server = result.server();
      requireStatus("start authenticated JDBC server", status);
      clientFile = TestTlsContexts.writeClientProperties(
          Files.createTempDirectory(root, "driver-client-"),
          DATABASE,
          1,
          server.port(),
          token);
    } catch (Exception failure) {
      throw new AssertionError("open authenticated JDBC fixture", failure);
    } finally {
      Arrays.fill(token, (byte) 0);
    }
  }

  private static void requireStatus(String operation, StatusCode status) {
    if (status != StatusCode.OK) throw statusFailure(operation, status);
  }

  private static AssertionError statusFailure(String operation, StatusCode status) {
    return new AssertionError(operation + ": " + status);
  }

  private static void closeDatabase(RiverDatabase database, Throwable primary) {
    if (database == null) return;
    try {
      requireStatus("close JDBC database after failure", database.close());
    } catch (Throwable cleanup) {
      primary.addSuppressed(cleanup);
    }
  }

  private static void rethrow(Throwable failure) {
    if (failure instanceof Error error) throw error;
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw new AssertionError("JDBC fixture failure", failure);
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
