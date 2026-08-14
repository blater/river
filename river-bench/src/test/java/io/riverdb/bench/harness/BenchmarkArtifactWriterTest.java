package io.riverdb.bench.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

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
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void fixedBufferedInputMatchesPreExtractionGoldenTree(@TempDir Path directory)
      throws IOException {
    byte[] payload = "key\tvalue\n1\t7\n".getBytes(StandardCharsets.UTF_8);
    WorkloadArtifact workload = new WorkloadArtifact(
        "riverbank_tiny",
        1,
        7,
        1,
        "schema=partial_tiny_v1;rows=1",
        payload,
        sha256(payload));

    withGoldenEnvironment(() -> {
      ArtifactWriteResult result = new BenchmarkArtifactWriter().write(
          directory,
          "golden-v1-run",
          Instant.EPOCH,
          "golden-commit",
          List.of(workload),
          List.of(sample(workload.name())),
          1_000_000,
          3);

      assertEquals(ArtifactWriteStatus.WRITTEN, result.status());
      assertGoldenTree(
          result.runDirectory(),
          "benchmark-artifact-v1-manifest.json",
          "benchmark-artifact-v1-result.json",
          "golden-v1-run",
          "riverbank_tiny",
          "riverbank_tiny-v1.tsv",
          1,
          payload);
    });
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
  void bufferedPreparationFailurePrecedesExistingTarget(@TempDir Path directory)
      throws IOException {
    Path existing = Files.createDirectory(directory.resolve("local-existing-invalid"));
    Path marker = existing.resolve("owned.txt");
    Files.writeString(marker, "first");
    WorkloadArtifact valid = new RiverBankGenerator().generate(7, 3).artifact();
    WorkloadArtifact wrongDigest = new WorkloadArtifact(
        valid.name(), valid.version(), valid.seed(), valid.recordCount(), valid.config(),
        valid.tsv(), "0".repeat(64));

    ArtifactWriteResult result = new BenchmarkArtifactWriter().write(
        directory, "local-existing-invalid", Instant.EPOCH, "commit",
        List.of(wrongDigest), List.of(sample(valid.name())), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.DIGEST_MISMATCH, result.status());
    assertEquals(null, result.runDirectory());
    assertEquals("first", Files.readString(marker));
    assertEquals(0, pendingDirectoryCount(directory));
  }

  @Test
  void incompleteClaimsBlockRetryAndAreNeverAutomaticallyReaped(@TempDir Path directory)
      throws IOException {
    String emptyRunId = "local-incomplete-empty";
    String markedRunId = "local-incomplete-marked";
    String stagedRunId = "local-incomplete-staged";
    Path empty = Files.createDirectory(directory.resolve(emptyRunId));
    Path marked = Files.createDirectory(directory.resolve(markedRunId));
    byte[] markedBytes = ("river-bench-claim-v1\nrun_id=" + markedRunId + "\n")
        .getBytes(StandardCharsets.UTF_8);
    Files.write(marked.resolve(".river-bench-claim-v1"), markedBytes);
    Path staged = Files.createDirectory(directory.resolve(stagedRunId));
    byte[] stagedClaimBytes = ("river-bench-claim-v1\nrun_id=" + stagedRunId + "\n")
        .getBytes(StandardCharsets.UTF_8);
    Files.write(staged.resolve(".river-bench-claim-v1"), stagedClaimBytes);
    Path pending = Files.createDirectory(staged.resolve(".pending"));
    Path partial = pending.resolve("manifest.json");
    Files.writeString(partial, "partial");
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();

    for (String runId : List.of(emptyRunId, markedRunId, stagedRunId)) {
      ArtifactWriteResult result = new BenchmarkArtifactWriter().write(
          directory, runId, Instant.EPOCH, "commit", List.of(workload),
          List.of(sample(workload.name())), 1_000_000, 3);
      assertEquals(ArtifactWriteStatus.TARGET_EXISTS, result.status());
    }

    assertTrue(Files.isDirectory(empty));
    assertArrayEquals(markedBytes, Files.readAllBytes(marked.resolve(".river-bench-claim-v1")));
    assertArrayEquals(
        stagedClaimBytes, Files.readAllBytes(staged.resolve(".river-bench-claim-v1")));
    assertEquals("partial", Files.readString(partial));
  }

  @Test
  void foreignAndSymbolicLinkClaimsAreUntouched(@TempDir Path directory) throws IOException {
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();
    Path foreign = Files.createDirectory(directory.resolve("local-foreign-claim"));
    Path foreignContent = foreign.resolve("foreign.txt");
    Files.writeString(foreignContent, "foreign");
    Path linkTarget = Files.createDirectory(directory.resolve("outside-link-target"));
    Path linkContent = linkTarget.resolve("foreign.txt");
    Files.writeString(linkContent, "linked-foreign");
    Path link = Files.createSymbolicLink(
        directory.resolve("local-symbolic-claim"), linkTarget);

    ArtifactWriteResult foreignResult = new BenchmarkArtifactWriter().write(
        directory, "local-foreign-claim", Instant.EPOCH, "commit", List.of(workload),
        List.of(sample(workload.name())), 1_000_000, 3);
    ArtifactWriteResult linkResult = new BenchmarkArtifactWriter().write(
        directory, "local-symbolic-claim", Instant.EPOCH, "commit", List.of(workload),
        List.of(sample(workload.name())), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.TARGET_EXISTS, foreignResult.status());
    assertEquals(ArtifactWriteStatus.TARGET_EXISTS, linkResult.status());
    assertEquals("foreign", Files.readString(foreignContent));
    assertTrue(Files.isSymbolicLink(link));
    assertEquals("linked-foreign", Files.readString(linkContent));
  }

  @Test
  void configuredOutputRootSymbolicLinkRemainsAllowed(@TempDir Path directory)
      throws IOException {
    Path destination = Files.createDirectory(directory.resolve("destination"));
    Path outputRoot = Files.createSymbolicLink(directory.resolve("configured-output"), destination);
    WorkloadArtifact workload = new RiverBankGenerator().generate(7, 3).artifact();

    ArtifactWriteResult result = new BenchmarkArtifactWriter().write(
        outputRoot, "local-root-link-01", Instant.EPOCH, "commit", List.of(workload),
        List.of(sample(workload.name())), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.WRITTEN, result.status());
    assertTrue(Files.exists(destination.resolve("local-root-link-01/artifacts/result.json")));
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

  private static void assertGoldenTree(
      Path run,
      String manifestResource,
      String resultResource,
      String runId,
      String workload,
      String workloadFile,
      int schemaVersion,
      byte[] payload) throws IOException {
    List<String> expectedNames = List.of(
        ".river-bench-staging-v1",
        "manifest.json",
        "result.json",
        workloadFile,
        "samples.tsv");
    try (Stream<Path> paths = Files.list(run)) {
      assertEquals(
          expectedNames,
          paths.map(path -> path.getFileName().toString()).sorted().toList());
    }
    byte[] manifest = goldenResource(manifestResource)
        .replace(
            "__AVAILABLE_PROCESSORS__",
            Integer.toString(Runtime.getRuntime().availableProcessors()))
        .getBytes(StandardCharsets.UTF_8);
    byte[] result = goldenResource(resultResource)
        .replace("__MANIFEST_SHA256__", sha256(manifest))
        .getBytes(StandardCharsets.UTF_8);
    String marker = "river-bench-staging-v1\nrun_id=" + runId + "\n";
    String samples = "schema_version\tworkload\tmode\tmetric\toperation_count\t"
        + "expected_interval_ns\thistogram_count\tminimum_ns\tp50_ns\tp95_ns\t"
        + "p99_ns\tp999_ns\tmaximum_ns\tmean_ns\n"
        + schemaVersion + "\t" + workload
        + "\tclosed_loop\tservice\t2\t0\t2\t10\t20\t30\t40\t50\t60\t25.5\n";

    assertArrayEquals(
        marker.getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(run.resolve(".river-bench-staging-v1")));
    assertArrayEquals(manifest, Files.readAllBytes(run.resolve("manifest.json")));
    assertArrayEquals(result, Files.readAllBytes(run.resolve("result.json")));
    assertArrayEquals(
        samples.getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(run.resolve("samples.tsv")));
    assertArrayEquals(payload, Files.readAllBytes(run.resolve(workloadFile)));
  }

  private static String goldenResource(String name) throws IOException {
    // Frozen from the accepted pre-extraction writer with pinned runtime properties.
    String path = "/io/riverdb/bench/harness/golden/" + name;
    try (InputStream input = BenchmarkArtifactWriterTest.class.getResourceAsStream(path)) {
      if (input == null) {
        throw new IOException("missing golden resource " + path);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void withGoldenEnvironment(ThrowingIoAction action) throws IOException {
    String[] names = {
        "os.name", "os.version", "os.arch", "java.runtime.version", "java.vm.name"
    };
    String[] values = {"GoldenOS", "1.0", "golden-arch", "25-golden", "GoldenVM"};
    String[] previous = new String[names.length];
    for (int index = 0; index < names.length; index++) {
      previous[index] = System.getProperty(names[index]);
      System.setProperty(names[index], values[index]);
    }
    try {
      action.run();
    } finally {
      for (int index = 0; index < names.length; index++) {
        if (previous[index] == null) {
          System.clearProperty(names[index]);
        } else {
          System.setProperty(names[index], previous[index]);
        }
      }
    }
  }

  @FunctionalInterface
  private interface ThrowingIoAction {
    void run() throws IOException;
  }

  private static long pendingDirectoryCount(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory, 2)) {
      return paths.filter(path -> path.getFileName().toString().equals(".pending"))
          .count();
    }
  }
}
