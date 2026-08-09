package io.riverdb.bench.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BenchmarkArtifactWriterTest {
  @Test
  void writesValidatedArtifactsOnceAndPreservesFirstRun(@TempDir Path directory)
      throws IOException {
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();
    LatencySnapshot latency = new LatencySnapshot(2, 10, 20, 30, 40, 50, 60, 25.5);
    SampleArtifact sample = new SampleArtifact(
        "riverbank", "closed_loop", "service", 2, 0, latency);
    BenchmarkArtifactWriter writer = new BenchmarkArtifactWriter();

    ArtifactWriteResult first = writer.write(
        directory,
        "local-test-0001",
        Instant.parse("2026-08-09T14:22:00Z"),
        "test-commit",
        List.of(workload),
        List.of(sample),
        1_000_000,
        3);
    Path manifest = first.runDirectory().resolve("manifest.json");
    byte[] originalManifest = Files.readAllBytes(manifest);
    ArtifactWriteResult second = writer.write(
        directory,
        "local-test-0001",
        Instant.parse("2026-08-09T14:23:00Z"),
        "other-commit",
        List.of(workload),
        List.of(sample),
        1_000_000,
        3);

    assertEquals(ArtifactWriteStatus.WRITTEN, first.status());
    assertEquals(ArtifactWriteStatus.TARGET_EXISTS, second.status());
    assertArrayEquals(originalManifest, Files.readAllBytes(manifest));
    assertTrue(Files.readString(first.runDirectory().resolve("samples.tsv"))
        .startsWith("schema_version\tworkload\tmode"));
    assertTrue(Files.readString(first.runDirectory().resolve("result.json"),
        StandardCharsets.UTF_8).contains("developer_smoke_not_promotion_evidence"));
    assertArrayEquals(workload.tsv(), Files.readAllBytes(
        first.runDirectory().resolve("riverbank-v1.tsv")));
  }

  @Test
  void rejectsInvalidArtifactBeforeCreatingDirectory(@TempDir Path directory)
      throws IOException {
    BenchmarkArtifactWriter writer = new BenchmarkArtifactWriter();

    ArtifactWriteResult result = writer.write(
        directory,
        "short",
        Instant.parse("2026-08-09T14:22:00Z"),
        "test-commit",
        List.of(),
        List.of(),
        1_000_000,
        3);

    assertEquals(ArtifactWriteStatus.INVALID_DOCUMENT, result.status());
    assertTrue(result.validation().errors().size() >= 2);
    assertTrue(Files.notExists(directory.resolve("short")));
  }
}
