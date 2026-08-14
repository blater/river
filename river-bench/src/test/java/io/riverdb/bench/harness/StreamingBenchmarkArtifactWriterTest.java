package io.riverdb.bench.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

final class StreamingBenchmarkArtifactWriterTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void streamsVersionedTablesIntoCreateOnceVerifiedArtifact(@TempDir Path directory)
      throws IOException {
    List<StreamingWorkloadArtifact> artifacts = new ArrayList<>();
    artifacts.addAll(new RiverBankStreamingGenerator()
        .plan(17, RiverBankScale.developerSmoke()).artifacts());
    artifacts.addAll(new RiverPapersStreamingGenerator()
        .plan(29, RiverPapersScale.developerSmoke()).artifacts());
    List<SampleArtifact> samples = List.of(
        sample("riverbank_transactions"), sample("riverpapers_documents"));
    BenchmarkArtifactWriter writer = new BenchmarkArtifactWriter();

    ArtifactWriteResult first = writer.writeStreaming(
        directory,
        "local-streaming-0001",
        Instant.parse("2026-08-09T14:22:00Z"),
        "test-commit",
        artifacts,
        samples,
        1_000_000,
        3);
    ArtifactWriteResult second = writer.writeStreaming(
        directory,
        "local-streaming-0001",
        Instant.parse("2026-08-09T14:23:00Z"),
        "other-commit",
        artifacts,
        samples,
        1_000_000,
        3);

    assertEquals(ArtifactWriteStatus.WRITTEN, first.status(), String.valueOf(first.validation()));
    assertEquals(ArtifactWriteStatus.TARGET_EXISTS, second.status());
    JsonNode manifest = MAPPER.readTree(first.runDirectory().resolve("manifest.json").toFile());
    JsonNode result = MAPPER.readTree(first.runDirectory().resolve("result.json").toFile());
    assertEquals(2, manifest.path("schema_version").asInt());
    assertEquals(2, result.path("schema_version").asInt());
    assertEquals(artifacts.size(), manifest.path("workloads").size());
    assertEquals(artifacts.size(), result.path("workload_artifacts").size());
    for (JsonNode workload : manifest.path("workloads")) {
      assertTrue(workload.path("record_count").asLong() > 0);
      assertTrue(workload.path("byte_count").asLong() > 0);
      assertTrue(workload.path("schema_id").textValue().endsWith(".v2"));
      assertEquals(64, workload.path("sha256").textValue().length());
    }
    for (JsonNode reference : result.path("workload_artifacts")) {
      Path payload = first.runDirectory().resolve(reference.path("path").textValue());
      assertEquals(reference.path("byte_count").asLong(), Files.size(payload));
      assertEquals(reference.path("sha256").textValue(),
          WorkloadChecksums.sha256(Files.readAllBytes(payload)));
    }
    assertEquals(0, pendingDirectoryCount(directory));
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void fixedStreamingInputMatchesPreExtractionGoldenTree(@TempDir Path directory)
      throws IOException {
    byte[] payload = "key\tvalue\n1\t9\n".getBytes(StandardCharsets.UTF_8);
    StreamingWorkloadArtifact artifact = new StreamingWorkloadArtifact(
        "riverbank_equivalent",
        2,
        11,
        1,
        "riverbank.equivalent.v2",
        "schema=riverbank_v2;table=equivalent;external_dataset=none",
        (output, scratch) -> {
          output.write(payload);
          return new StreamingGenerationResult(
              StreamingGenerationStatus.GENERATED, 1, payload.length);
        });

    withGoldenEnvironment(() -> {
      ArtifactWriteResult result = new BenchmarkArtifactWriter().writeStreaming(
          directory,
          "golden-v2-run",
          Instant.EPOCH,
          "golden-commit",
          List.of(artifact),
          List.of(sample(artifact.name())),
          1_000_000,
          3);

      assertEquals(ArtifactWriteStatus.WRITTEN, result.status());
      assertGoldenTree(result.runDirectory(), payload);
    });
  }

  @Test
  void invokesEmitterExactlyTwiceWithBoundedNonEmptyScratch(@TempDir Path directory)
      throws IOException {
    AtomicInteger calls = new AtomicInteger();
    List<Integer> scratchLengths = new ArrayList<>();
    StreamingWorkloadArtifact artifact = fixedArtifact(
        "riverbank_two_pass", calls, scratchLengths);

    ArtifactWriteResult result = new BenchmarkArtifactWriter().writeStreaming(
        directory, "local-two-pass-01", Instant.EPOCH, "commit",
        List.of(artifact), List.of(sample(artifact.name())), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.WRITTEN, result.status());
    assertEquals(2, calls.get());
    assertEquals(2, scratchLengths.size());
    for (int scratchLength : scratchLengths) {
      assertTrue(scratchLength > 0);
      assertTrue(scratchLength <= 64 * 1024);
    }
  }

  @Test
  void duplicateStreamingOutputNamesCleanTheClaim(@TempDir Path directory)
      throws IOException {
    StreamingWorkloadArtifact artifact = new RiverBankStreamingGenerator()
        .plan(17, RiverBankScale.developerSmoke()).artifacts().get(0);

    ArtifactWriteResult result = new BenchmarkArtifactWriter().writeStreaming(
        directory, "local-streaming-duplicate", Instant.EPOCH, "commit",
        List.of(artifact, artifact), List.of(sample(artifact.name())), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.DUPLICATE_OUTPUT_NAME, result.status());
    assertFalse(Files.exists(directory.resolve("local-streaming-duplicate")));
  }

  @Test
  void detectsSourceChangeBetweenPreflightAndStage(@TempDir Path directory) {
    AtomicInteger passes = new AtomicInteger();
    StreamingWorkloadArtifact unstable = new StreamingWorkloadArtifact(
        "riverbank_unstable",
        2,
        1,
        1,
        "riverbank.unstable.v2",
        "schema=riverbank_v2;table=unstable;external_dataset=none",
        (output, scratch) -> {
          byte value = passes.getAndIncrement() == 0 ? (byte) 'a' : (byte) 'b';
          output.write(new byte[] {'h', '\n', value, '\n'});
          return new StreamingGenerationResult(
              StreamingGenerationStatus.GENERATED, 1, 4);
        });

    assertThrows(IOException.class, () -> new BenchmarkArtifactWriter().writeStreaming(
        directory, "local-streaming-unstable", Instant.EPOCH, "commit",
        List.of(unstable), List.of(sample(unstable.name())), 1_000_000, 3));
    assertFalse(Files.exists(directory.resolve("local-streaming-unstable")));
  }

  @Test
  void independentlyRejectsLyingEmitterCounts(@TempDir Path directory) throws IOException {
    StreamingWorkloadArtifact wrongRows = lyingArtifact("riverbank_wrong_rows", 1, 4,
        new byte[] {'h', '\n', 'a', '\n', 'b', '\n'});
    StreamingWorkloadArtifact wrongBytes = lyingArtifact("riverbank_wrong_bytes", 1, 99,
        new byte[] {'h', '\n', 'a', '\n'});
    BenchmarkArtifactWriter writer = new BenchmarkArtifactWriter();

    ArtifactWriteResult rows = writer.writeStreaming(
        directory, "local-lying-rows", Instant.EPOCH, "commit", List.of(wrongRows),
        List.of(sample(wrongRows.name())), 1_000_000, 3);
    ArtifactWriteResult bytes = writer.writeStreaming(
        directory, "local-lying-bytes", Instant.EPOCH, "commit", List.of(wrongBytes),
        List.of(sample(wrongBytes.name())), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.WORKLOAD_GENERATION_FAILED, rows.status());
    assertEquals(ArtifactWriteStatus.WORKLOAD_GENERATION_FAILED, bytes.status());
    assertFalse(Files.exists(directory.resolve("local-lying-rows")));
    assertFalse(Files.exists(directory.resolve("local-lying-bytes")));
  }

  @Test
  void independentlyRejectsSecondPassCountLie(@TempDir Path directory) {
    AtomicInteger passes = new AtomicInteger();
    StreamingWorkloadArtifact unstable = new StreamingWorkloadArtifact(
        "riverbank_second_pass_lie", 2, 1, 1, "riverbank.second_pass_lie.v2",
        "schema=riverbank_v2;table=second_pass_lie;external_dataset=none",
        (output, scratch) -> {
          output.write(new byte[] {'h', '\n', 'a', '\n'});
          int pass = passes.getAndIncrement();
          return new StreamingGenerationResult(
              StreamingGenerationStatus.GENERATED, pass == 0 ? 1 : 2, 4);
        });

    assertThrows(IOException.class, () -> new BenchmarkArtifactWriter().writeStreaming(
        directory, "local-second-pass-lie", Instant.EPOCH, "commit", List.of(unstable),
        List.of(sample(unstable.name())), 1_000_000, 3));
    assertFalse(Files.exists(directory.resolve("local-second-pass-lie")));
  }

  @Test
  void rejectsInvalidOrExistingTargetBeforeInvokingEmitter(@TempDir Path directory)
      throws IOException {
    AtomicInteger calls = new AtomicInteger();
    StreamingWorkloadArtifact artifact = new StreamingWorkloadArtifact(
        "riverbank_probe", 2, 1, 1, "riverbank.probe.v2",
        "schema=riverbank_v2;table=probe;external_dataset=none",
        (output, scratch) -> {
          calls.incrementAndGet();
          output.write(new byte[] {'h', '\n', 'a', '\n'});
          return new StreamingGenerationResult(StreamingGenerationStatus.GENERATED, 1, 4);
        });
    Files.createDirectory(directory.resolve("local-existing-target"));
    BenchmarkArtifactWriter writer = new BenchmarkArtifactWriter();

    ArtifactWriteResult invalid = writer.writeStreaming(
        directory, "../escape", Instant.EPOCH, "commit", List.of(artifact),
        List.of(sample(artifact.name())), 1_000_000, 3);
    ArtifactWriteResult existing = writer.writeStreaming(
        directory, "local-existing-target", Instant.EPOCH, "commit", List.of(artifact),
        List.of(sample(artifact.name())), 1_000_000, 3);

    assertEquals(ArtifactWriteStatus.INVALID_DOCUMENT, invalid.status());
    assertEquals(ArtifactWriteStatus.TARGET_EXISTS, existing.status());
    assertEquals(0, calls.get());
  }

  private static StreamingWorkloadArtifact lyingArtifact(
      String name,
      long rows,
      long bytes,
      byte[] payload) {
    return new StreamingWorkloadArtifact(
        name,
        2,
        1,
        1,
        "riverbank." + name.substring("riverbank_".length()) + ".v2",
        "schema=riverbank_v2;table=" + name.substring("riverbank_".length())
            + ";external_dataset=none",
        (output, scratch) -> {
          output.write(payload);
          return new StreamingGenerationResult(
              StreamingGenerationStatus.GENERATED, rows, bytes);
        });
  }

  private static StreamingWorkloadArtifact fixedArtifact(
      String name,
      AtomicInteger calls,
      List<Integer> scratchLengths) {
    String suffix = name.substring("riverbank_".length());
    return new StreamingWorkloadArtifact(
        name,
        2,
        1,
        1,
        "riverbank." + suffix + ".v2",
        "schema=riverbank_v2;table=" + suffix + ";external_dataset=none",
        (output, scratch) -> {
          if (calls != null) {
            calls.incrementAndGet();
          }
          if (scratchLengths != null) {
            scratchLengths.add(scratch.length);
          }
          output.write(new byte[] {'h', '\n', 'a', '\n'});
          return new StreamingGenerationResult(StreamingGenerationStatus.GENERATED, 1, 4);
        });
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

  private static void assertGoldenTree(Path run, byte[] payload) throws IOException {
    List<String> expectedNames = List.of(
        ".river-bench-staging-v1",
        "manifest.json",
        "result.json",
        "riverbank_equivalent-v2.tsv",
        "samples.tsv");
    try (Stream<Path> paths = Files.list(run)) {
      assertEquals(
          expectedNames,
          paths.map(path -> path.getFileName().toString()).sorted().toList());
    }
    byte[] manifest = goldenResource("benchmark-artifact-v2-manifest.json")
        .replace(
            "__AVAILABLE_PROCESSORS__",
            Integer.toString(Runtime.getRuntime().availableProcessors()))
        .getBytes(StandardCharsets.UTF_8);
    byte[] result = goldenResource("benchmark-artifact-v2-result.json")
        .replace("__MANIFEST_SHA256__", sha256(manifest))
        .getBytes(StandardCharsets.UTF_8);
    String marker = "river-bench-staging-v1\nrun_id=golden-v2-run\n";
    String samples = "schema_version\tworkload\tmode\tmetric\toperation_count\t"
        + "expected_interval_ns\thistogram_count\tminimum_ns\tp50_ns\tp95_ns\t"
        + "p99_ns\tp999_ns\tmaximum_ns\tmean_ns\n"
        + "2\triverbank_equivalent\tclosed_loop\tservice\t2\t0\t2\t10\t20\t30\t"
        + "40\t50\t60\t25.5\n";

    assertArrayEquals(
        marker.getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(run.resolve(".river-bench-staging-v1")));
    assertArrayEquals(manifest, Files.readAllBytes(run.resolve("manifest.json")));
    assertArrayEquals(result, Files.readAllBytes(run.resolve("result.json")));
    assertArrayEquals(
        samples.getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(run.resolve("samples.tsv")));
    assertArrayEquals(
        payload,
        Files.readAllBytes(run.resolve("riverbank_equivalent-v2.tsv")));
  }

  private static String goldenResource(String name) throws IOException {
    // Frozen from the accepted pre-extraction writer with pinned runtime properties.
    String path = "/io/riverdb/bench/harness/golden/" + name;
    try (InputStream input = StreamingBenchmarkArtifactWriterTest.class
        .getResourceAsStream(path)) {
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
