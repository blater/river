package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
import io.riverdb.server.LoopbackServerLimits;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the generated client file through the real JDBC client-file URL. */
final class RiverDaemonInstanceJdbcIntegrationTest {
  @Test
  void failedDatabaseCloseRetainsIdentityUntilOpenSessionCloses(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    DatabaseResourcePlanRequest plan = new DatabaseResourcePlanRequest()
        .memory(256_000_000L, 0, 0, 0, 64_000_000L)
        .lockProviderBytes(8_000_000L)
        .versionWorkspaceBytes(8_000_000L)
        .indexedPageCache(32_000_000L, 8_000_000L)
        .capacity(8, Integer.MAX_VALUE, 800L, 64_000_000L)
        .maximumDelivery(Integer.MAX_VALUE, 800L, 64_000_000L);
    RiverDaemonInstance.OpenResult opened = new RiverDaemonInstance.OpenResult();
    StatusCode status = RiverDaemonInstance.open(
        datadir,
        new ApfsRiverDaemonFileSystem(),
        new SecureRandom(),
        DatabaseIncarnation.of(31, 47),
        "127.0.0.1",
        InetAddress.getByName("127.0.0.1"),
        0,
        LoopbackServerLimits.defaults(8),
        plan,
        EmbeddedLockDiagnosticsConfig.disabled(),
        8,
        opened);
    assertEquals(StatusCode.OK, status);
    RiverDaemonInstance instance = opened.instance();
    RiverSession session = null;
    try {
      SessionOpenResult sessionResult = new SessionOpenResult();
      assertEquals(StatusCode.OK, instance.database().createSession(sessionResult));
      session = sessionResult.session();
      assertNotNull(session);

      assertEquals(StatusCode.OK, instance.server().close());
      assertEquals(StatusCode.CONFLICT, instance.close());
      assertFalse(instance.servicesClosed());
      assertNotNull(instance.identity);
      assertNotNull(opened.identity());
      assertNotNull(instance.database());

      assertEquals(StatusCode.OK, session.close());
      session = null;
      assertEquals(StatusCode.OK, instance.close());
      assertTrue(instance.servicesClosed());
      assertEquals(null, instance.identity);
      assertEquals(null, opened.identity());
    } finally {
      if (session != null) session.close();
      if (!instance.servicesClosed()) instance.close();
    }
  }

  @Test
  void generatedClientFileConnectsThroughJdbc(@TempDir Path root) throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    DatabaseResourcePlanRequest plan = new DatabaseResourcePlanRequest()
        .memory(256_000_000L, 0, 0, 0, 64_000_000L)
        .lockProviderBytes(8_000_000L)
        .versionWorkspaceBytes(8_000_000L)
        .indexedPageCache(32_000_000L, 8_000_000L)
        .capacity(8, Integer.MAX_VALUE, 800L, 64_000_000L)
        .maximumDelivery(Integer.MAX_VALUE, 800L, 64_000_000L);
    RiverDaemonInstance.OpenResult opened = new RiverDaemonInstance.OpenResult();
    StatusCode status = RiverDaemonInstance.open(
        datadir,
        new ApfsRiverDaemonFileSystem(),
        new SecureRandom(),
        DatabaseIncarnation.of(17, 29),
        "127.0.0.1",
        InetAddress.getByName("127.0.0.1"),
        0,
        LoopbackServerLimits.defaults(8),
        plan,
        EmbeddedLockDiagnosticsConfig.disabled(),
        8,
        opened);
    assertEquals(StatusCode.OK, status);
    try {
      RiverDaemonInstance instance = opened.instance();
      assertNotNull(instance);
      assertFalse(instance.servicesClosed());
      assertEquals(StatusCode.OK, instance.checkCredentialValidity());
      assertNotNull(instance.clientConfiguration());
      try (Connection connection = DriverManager.getConnection(
          "jdbc:river:client-file:" + instance.clientConfiguration())) {
        assertFalse(connection.isClosed());
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
          statement.executeUpdate("CREATE TABLE generated_client_probe (id BIGINT PRIMARY KEY, value VARCHAR(16))");
          statement.executeUpdate("INSERT INTO generated_client_probe VALUES (1, 'connected')");
        }
        connection.commit();
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery(
                "SELECT id, value FROM generated_client_probe")) {
          assertTrue(rows.next());
          assertEquals(1L, rows.getLong(1));
          assertEquals("connected", rows.getString(2));
          assertFalse(rows.next());
        }
      }
    } finally {
      assertEquals(StatusCode.OK, opened.close());
    }
  }
}
