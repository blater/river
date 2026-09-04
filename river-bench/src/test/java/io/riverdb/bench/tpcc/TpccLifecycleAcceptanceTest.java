package io.riverdb.bench.tpcc;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Owns the real checkpoint, database close/open, and server restart lifecycle. */
final class TpccLifecycleAcceptanceTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5450_4343_4245_4E43L, 0x485F_4C49_4645_3031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void tinyNoWaitRunSurvivesDatabaseCloseAndOpen(@TempDir Path root) throws Exception {
    runLifecycle(root, true, 1, 2);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "RIVER_TPCC_STANDARD_SMOKE", matches = "true")
  void standardScaleShortRunSurvivesDatabaseCloseAndOpen(@TempDir Path root) throws Exception {
    runLifecycle(root, false, 1, 2);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "RIVER_TPCC_FULL", matches = "true")
  void fullPromotionRunSurvivesDatabaseCloseAndOpen(@TempDir Path root) throws Exception {
    runLifecycle(root, false, 300, 1_800);
  }

  private static void runLifecycle(Path root, boolean tiny, int warmup, int measured)
      throws Exception {
    Path databaseRoot = root.resolve("database");
    Path artifact = root.resolve("acceptance.properties");
    Files.createDirectory(databaseRoot);
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedRiver.create(
            databaseRequest(16), databaseRoot, DATABASE, GENERATION, 16, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    try {
      TpccAcceptanceMain.main(arguments(server.port(), artifact, tiny,
          "load-run-checkpoint", warmup, measured));
    } finally {
      assertEquals(StatusCode.OK, server.close());
      assertEquals(StatusCode.OK, database.close());
    }
    opened.reset();
    assertEquals(StatusCode.OK,
        EmbeddedRiver.openExisting(
            databaseRequest(16), databaseRoot, DATABASE, GENERATION, 16, opened));
    database = opened.database();
    server = start(database);
    try {
      TpccAcceptanceMain.main(arguments(server.port(), artifact, tiny,
          "recovery-verify", warmup, measured));
    } finally {
      assertEquals(StatusCode.OK, server.close());
      assertEquals(StatusCode.OK, database.close());
    }
    Properties evidence = new Properties();
    try (java.io.InputStream input = Files.newInputStream(artifact)) {
      evidence.load(input);
    }
    assertEquals(evidence.getProperty("run.id"), evidence.getProperty("recovery.run_id"));
    assertTrue(evidence.containsKey("recovery.verified_at"));
  }

  private static String[] arguments(
      int port, Path artifact, boolean tiny, String phase, int warmup, int measured) {
    java.util.ArrayList<String> values = new java.util.ArrayList<>();
    values.add("--url=jdbc:river://localhost:" + port);
    values.add("--artifact=" + artifact);
    values.add("--phase=" + phase);
    values.add("--warmup-seconds=" + warmup);
    values.add("--measured-seconds=" + measured);
    String jfr = System.getenv("RIVER_TPCC_TEST_JFR");
    if (jfr != null && phase.equals("load-run-checkpoint")) values.add("--jfr=" + jfr);
    if (tiny) {
      values.add("--tiny");
      values.add("--scheduling=no-wait-stress");
    }
    return values.toArray(String[]::new);
  }

  private static LoopbackRiverServer start(RiverDatabase database) {
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
    assertEquals(StatusCode.OK, LoopbackRiverServer.start(database, 0, result));
    return result.server();
  }
}
