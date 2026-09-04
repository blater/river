package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TpccArtifactTest {
  @Test
  void streamsEvidenceLargerThanFormerWholeFileLimit(@TempDir Path root) throws Exception {
    Path artifact = root.resolve("nested/tpcc.properties");
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9", "--tiny", "--terminals=20000",
        "--retry-maximum-millis=" + Long.MAX_VALUE,
        "--artifact=" + artifact
    });
    TpccProcessObservation observation = new TpccProcessObservation(0, 0, 0, 0, 0, 0);

    String runId = TpccArtifact.write(
        config, new TpccMetrics(), new TpccDatabaseIdentity("database-digest"),
        observation, observation, 0, 0);

    assertTrue(Files.size(artifact) > 64 * 1024);
    assertFalse(Files.exists(stagedPath(artifact)));
    TpccArtifact.Recovery recovery = TpccArtifact.read(config);
    assertEquals(runId, recovery.runId());
    assertEquals("database-digest", recovery.databaseDigest());

    TpccArtifact.markRecovered(config, recovery, "recovered-digest");
    Properties values = new Properties();
    try (InputStream input = Files.newInputStream(artifact)) {
      values.load(input);
    }
    assertEquals("recovered-digest", values.getProperty("recovery.database_digest.sha256"));
    assertEquals(Long.toString(Long.MAX_VALUE), values.getProperty("config.retry_maximum_nanos"));
    assertFalse(values.containsKey("bound.jdbc_batch_statements"));
    assertFalse(Files.exists(stagedPath(artifact)));
  }

  private static Path stagedPath(Path artifact) {
    Path absolute = artifact.toAbsolutePath();
    return absolute.resolveSibling(absolute.getFileName() + ".staged");
  }
}
