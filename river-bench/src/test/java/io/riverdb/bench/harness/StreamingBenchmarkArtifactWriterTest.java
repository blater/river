package io.riverdb.bench.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  void rejectsDuplicateStreamingOutputNamesBeforeCreatingRun(@TempDir Path directory)
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
