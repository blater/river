package io.riverdb.bench.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BenchmarkArtifactWriterTest {
  @Test
  void writesValidatedArtifactsOnceAndPreservesFirstRun(@TempDir Path directory)
      throws IOException {
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();
    LatencySnapshot latency = new LatencySnapshot(2, 10, 20, 30, 40, 50, 60, 25.5);
    SampleArtifact sample = new SampleArtifact(
        "riverbank_tiny", "closed_loop", "service", 2, 0, latency);
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
    assertEquals(directory.resolve("local-test-0001").resolve("artifacts"),
        first.runDirectory());
    assertEquals(ArtifactWriteStatus.TARGET_EXISTS, second.status());
    assertArrayEquals(originalManifest, Files.readAllBytes(manifest));
    assertTrue(Files.readString(first.runDirectory().resolve("samples.tsv"))
        .startsWith("schema_version\tworkload\tmode"));
    assertTrue(Files.readString(first.runDirectory().resolve("result.json"),
        StandardCharsets.UTF_8).contains("developer_smoke_not_promotion_evidence"));
    assertArrayEquals(workload.tsv(), Files.readAllBytes(
        first.runDirectory().resolve("riverbank_tiny-v1.tsv")));
    assertEquals(0, pendingDirectoryCount(directory));
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

  @Test
  void rejectsDigestMismatchDuplicateNamesAndDanglingSamples(@TempDir Path directory)
      throws IOException {
    WorkloadArtifact valid = new RiverBankGenerator().generate(7, 3).artifact();
    WorkloadArtifact wrongDigest = new WorkloadArtifact(
        valid.name(), valid.version(), valid.seed(), valid.recordCount(), valid.config(),
        valid.tsv(), "0".repeat(64));
    SampleArtifact validSample = sample("riverbank_tiny");
    BenchmarkArtifactWriter writer = new BenchmarkArtifactWriter();

    ArtifactWriteResult digest = writer.write(
        directory, "local-digest-01", Instant.EPOCH, "commit", List.of(wrongDigest),
        List.of(validSample), 1_000_000, 3);
    ArtifactWriteResult duplicate = writer.write(
        directory, "local-duplicate-01", Instant.EPOCH, "commit", List.of(valid, valid),
        List.of(validSample), 1_000_000, 3);
    ArtifactWriteResult dangling = writer.write(
        directory, "local-dangling-01", Instant.EPOCH, "commit", List.of(valid),
        List.of(sample("riverpapers_tiny")), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.DIGEST_MISMATCH, digest.status());
    assertEquals(ArtifactWriteStatus.DUPLICATE_OUTPUT_NAME, duplicate.status());
    assertEquals(ArtifactWriteStatus.INVALID_DOCUMENT, dangling.status());
    assertFalse(Files.exists(directory.resolve("local-digest-01")));
    assertFalse(Files.exists(directory.resolve("local-duplicate-01")));
    assertFalse(Files.exists(directory.resolve("local-dangling-01")));
    assertEquals(0, pendingDirectoryCount(directory));
  }

  @Test
  void injectedFailuresNeverPublishPartialRunAndCleanStaging(@TempDir Path directory)
      throws IOException {
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();
    List<SampleArtifact> samples = List.of(sample("riverbank_tiny"));

    assertThrows(IOException.class, () -> new BenchmarkArtifactWriter(
        ArtifactWriteFailure.AFTER_FIRST_PAYLOAD).write(
            directory, "local-failure-01", Instant.EPOCH, "commit", List.of(workload),
            samples, 1_000_000, 3));
    assertFalse(Files.exists(directory.resolve("local-failure-01")));
    assertEquals(0, pendingDirectoryCount(directory));

    assertThrows(IOException.class, () -> new BenchmarkArtifactWriter(
        ArtifactWriteFailure.BEFORE_PUBLISH).write(
            directory, "local-failure-02", Instant.EPOCH, "commit", List.of(workload),
            samples, 1_000_000, 3));
    assertFalse(Files.exists(directory.resolve("local-failure-02")));
    assertEquals(0, pendingDirectoryCount(directory));

    assertThrows(IOException.class, () -> new BenchmarkArtifactWriter(
        ArtifactWriteFailure.CORRUPT_FIRST_WORKLOAD).write(
            directory, "local-failure-03", Instant.EPOCH, "commit", List.of(workload),
            samples, 1_000_000, 3));
    assertFalse(Files.exists(directory.resolve("local-failure-03")));
    assertEquals(0, pendingDirectoryCount(directory));
  }

  @Test
  void existingRunCollisionIsPreservedWithoutStaging(@TempDir Path directory)
      throws IOException {
    Path existing = Files.createDirectory(directory.resolve("local-collision-01"));
    Path marker = existing.resolve("owned.txt");
    Files.writeString(marker, "first");
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();

    ArtifactWriteResult result = new BenchmarkArtifactWriter().write(
        directory, "local-collision-01", Instant.EPOCH, "commit", List.of(workload),
        List.of(sample("riverbank_tiny")), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.TARGET_EXISTS, result.status());
    assertEquals("first", Files.readString(marker));
    assertEquals(0, pendingDirectoryCount(directory));
  }

  @Test
  void retryRecoversOnlyOwnedExpectedStaging(@TempDir Path directory) throws IOException {
    String runId = "local-recover-01";
    Path staging = Files.createDirectory(directory.resolve(".pending-" + runId + "-abandoned"));
    Files.writeString(staging.resolve(".river-bench-staging-v1"),
        "river-bench-staging-v1\nrun_id=" + runId + "\n");
    Files.writeString(staging.resolve("manifest.json"), "partial");
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();

    ArtifactWriteResult result = new BenchmarkArtifactWriter().write(
        directory, runId, Instant.EPOCH, "commit", List.of(workload),
        List.of(sample("riverbank_tiny")), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.WRITTEN, result.status());
    assertFalse(Files.exists(staging));
    assertTrue(Files.exists(result.runDirectory().resolve("result.json")));
    assertEquals(0, pendingDirectoryCount(directory));
  }

  @Test
  void recoveryRefusesUnexpectedStagingContent(@TempDir Path directory) throws IOException {
    String runId = "local-recover-unsafe-01";
    Path staging = Files.createDirectory(directory.resolve(".pending-" + runId + "-abandoned"));
    Files.writeString(staging.resolve(".river-bench-staging-v1"),
        "river-bench-staging-v1\nrun_id=" + runId + "\n");
    Path unexpected = staging.resolve("do-not-delete.txt");
    Files.writeString(unexpected, "owned elsewhere");
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();

    assertThrows(IOException.class, () -> new BenchmarkArtifactWriter().write(
        directory, runId, Instant.EPOCH, "commit", List.of(workload),
        List.of(sample("riverbank_tiny")), 1_000_000, 3));
    assertEquals("owned elsewhere", Files.readString(unexpected));
    assertFalse(Files.exists(directory.resolve(runId)));
  }

  @Test
  void recoveryRefusesEmptyUnmarkedStaging(@TempDir Path directory) throws IOException {
    String runId = "local-recover-empty-01";
    Path staging = Files.createDirectory(directory.resolve(".pending-" + runId + "-foreign"));
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();

    assertThrows(IOException.class, () -> new BenchmarkArtifactWriter().write(
        directory, runId, Instant.EPOCH, "commit", List.of(workload),
        List.of(sample("riverbank_tiny")), 1_000_000, 3));

    assertTrue(Files.isDirectory(staging));
    try (Stream<Path> paths = Files.list(staging)) {
      assertEquals(0, paths.count());
    }
    assertFalse(Files.exists(directory.resolve(runId)));
  }

  @Test
  void competitorClaimBetweenPreflightAndPublishIsNeverOverwritten(
      @TempDir Path directory) throws IOException {
    String runId = "local-race-claim-01";
    Path runDirectory = directory.resolve(runId);
    Path competitor = runDirectory.resolve("competitor.txt");
    ArtifactPublishProbe competitorClaims = target -> {
      assertEquals(runDirectory, target);
      Files.createDirectory(target);
      Files.writeString(competitor, "competitor-owned");
    };
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();

    ArtifactWriteResult result = new BenchmarkArtifactWriter(
        ArtifactWriteFailure.NONE, competitorClaims).write(
            directory, runId, Instant.EPOCH, "commit", List.of(workload),
            List.of(sample("riverbank_tiny")), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.TARGET_EXISTS, result.status());
    assertEquals("competitor-owned", Files.readString(competitor));
    assertFalse(Files.exists(runDirectory.resolve("artifacts")));
    assertEquals(0, pendingDirectoryCount(directory));
  }

  private static SampleArtifact sample(String workload) {
    return new SampleArtifact(
        workload,
        "closed_loop",
        "service",
        2,
        0,
        new LatencySnapshot(2, 10, 20, 30, 40, 50, 60, 25.5));
  }

  private static long pendingDirectoryCount(Path directory) throws IOException {
    try (Stream<Path> paths = Files.list(directory)) {
      return paths.filter(path -> path.getFileName().toString().startsWith(".pending-"))
          .count();
    }
  }
}
