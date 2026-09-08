package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Owns the real checkpoint, database close/open, and process restart lifecycle. */
final class TpccLifecycleAcceptanceTest {
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
    Path artifact = root.resolve("acceptance.properties");
    runChild(root, artifact, tiny, "load-run-checkpoint", warmup, measured);
    runChild(root, artifact, tiny, "recovery-verify", warmup, measured);
    Properties evidence = new Properties();
    try (java.io.InputStream input = Files.newInputStream(artifact)) {
      evidence.load(input);
    }
    assertEquals(evidence.getProperty("run.id"), evidence.getProperty("recovery.run_id"));
    assertTrue(evidence.containsKey("recovery.verified_at"));
  }

  /** Runs each phase in a fresh JVM so identity restart uses a distinct process owner. */
  private static void runChild(
      Path root, Path artifact, boolean tiny, String phase, int warmup, int measured)
      throws Exception {
    String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    java.util.ArrayList<String> command = new java.util.ArrayList<>();
    command.add(javaExecutable);
    command.add("--enable-native-access=ALL-UNNAMED");
    command.add("-Xmx1g");
    command.add("-cp");
    command.add(System.getProperty(
        "river.test.classpath", System.getProperty("java.class.path")));
    command.add(TpccLifecycleChildMain.class.getName());
    command.add("--root=" + root.toAbsolutePath());
    command.add("--artifact=" + artifact.toAbsolutePath());
    command.add("--phase=" + phase);
    command.add("--warmup-seconds=" + warmup);
    command.add("--measured-seconds=" + measured);
    if (tiny) command.add("--tiny");
    Process process = new ProcessBuilder(command)
        .inheritIO()
        .start();
    long timeoutSeconds = Math.max(60L, (long) warmup + measured + 120L);
    try {
      assertTrue(
          process.waitFor(timeoutSeconds, TimeUnit.SECONDS),
          "TPC-C child timed out after " + timeoutSeconds + " seconds");
      assertEquals(0, process.exitValue());
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

}
