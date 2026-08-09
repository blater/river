package io.riverdb.bench.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Preflights, stages, verifies, and publishes a create-once local run.
 *
 * <p>The run directory is an exclusive claim and is not itself a completion signal. Readers
 * consider only the atomically installed {@code artifacts/} child, and must validate its
 * {@code result.json} plus referenced digests before accepting the run as complete.
 */
public final class BenchmarkArtifactWriter {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ObjectWriter JSON = MAPPER.writerWithDefaultPrettyPrinter();
  private static final String SAMPLE_HEADER = String.join("\t",
      "schema_version", "workload", "mode", "metric", "operation_count",
      "expected_interval_ns", "histogram_count", "minimum_ns", "p50_ns",
      "p95_ns", "p99_ns", "p999_ns", "maximum_ns", "mean_ns") + "\n";
  private static final String STAGING_MARKER = ".river-bench-staging-v1";
  private static final String CLAIM_MARKER = ".river-bench-claim-v1";
  private static final String PUBLISHED_DIRECTORY = "artifacts";
  private static final int STREAMING_SCRATCH_BYTES = 64 * 1024;

  private final BenchmarkSchemaValidator validator = new BenchmarkSchemaValidator();
  private final ArtifactWriteFailure failure;
  private final ArtifactPublishProbe publishProbe;

  public BenchmarkArtifactWriter() {
    this(ArtifactWriteFailure.NONE, ArtifactPublishProbe.NONE);
  }

  BenchmarkArtifactWriter(ArtifactWriteFailure failure) {
    this(failure, ArtifactPublishProbe.NONE);
  }

  BenchmarkArtifactWriter(
      ArtifactWriteFailure failure,
      ArtifactPublishProbe publishProbe) {
    this.failure = failure;
    this.publishProbe = publishProbe;
  }

  public ArtifactWriteResult write(
      Path outputRoot,
      String runId,
      Instant createdAt,
      String riverCommit,
      List<WorkloadArtifact> workloads,
      List<SampleArtifact> samples,
      long highestTrackableNanos,
      int significantDigits) throws IOException {
    PreparedWorkloads preparedWorkloads = prepareWorkloads(workloads);
    return writePrepared(
        outputRoot,
        runId,
        createdAt,
        riverCommit,
        preparedWorkloads,
        samples,
        highestTrackableNanos,
        significantDigits,
        1,
        BenchmarkSchemaValidator.MANIFEST,
        BenchmarkSchemaValidator.RESULT,
        BenchmarkSchemaValidator.SAMPLE);
  }

  /** Publishes deterministic table streams without retaining their payloads in heap. */
  public ArtifactWriteResult writeStreaming(
      Path outputRoot,
      String runId,
      Instant createdAt,
      String riverCommit,
      List<StreamingWorkloadArtifact> workloads,
      List<SampleArtifact> samples,
      long highestTrackableNanos,
      int significantDigits) throws IOException {
    PreparedWorkloads preparedWorkloads = prepareStreamingWorkloads(workloads);
    return writePrepared(
        outputRoot,
        runId,
        createdAt,
        riverCommit,
        preparedWorkloads,
        samples,
        highestTrackableNanos,
        significantDigits,
        2,
        BenchmarkSchemaValidator.STREAMING_MANIFEST,
        BenchmarkSchemaValidator.STREAMING_RESULT,
        BenchmarkSchemaValidator.STREAMING_SAMPLE);
  }

  private ArtifactWriteResult writePrepared(
      Path outputRoot,
      String runId,
      Instant createdAt,
      String riverCommit,
      PreparedWorkloads preparedWorkloads,
      List<SampleArtifact> samples,
      long highestTrackableNanos,
      int significantDigits,
      int schemaVersion,
      String manifestSchema,
      String resultSchema,
      String sampleSchema) throws IOException {
    if (preparedWorkloads.status() != ArtifactWriteStatus.WRITTEN) {
      return new ArtifactWriteResult(preparedWorkloads.status(), null, null);
    }
    byte[] manifestBytes = jsonBytes(manifest(
        schemaVersion,
        runId,
        createdAt,
        riverCommit,
        preparedWorkloads.workloads(),
        highestTrackableNanos,
        significantDigits));
    SchemaValidation manifestValidation = validate(
        manifestSchema, manifestBytes);
    if (!manifestValidation.valid()) {
      return invalid(manifestValidation);
    }

    StringBuilder sampleTsv = new StringBuilder(SAMPLE_HEADER);
    Set<String> workloadNames = new HashSet<>();
    preparedWorkloads.workloads().forEach(workload -> workloadNames.add(workload.name()));
    for (SampleArtifact sample : samples) {
      if (!workloadNames.contains(sample.workload())) {
        return invalid(new SchemaValidation(
            false, List.of("$.workload: sample references an absent workload")));
      }
      SchemaValidation sampleValidation = validator.validate(
          sampleSchema, MAPPER.writeValueAsString(sample(schemaVersion, sample)));
      if (!sampleValidation.valid()) {
        return invalid(sampleValidation);
      }
      appendSample(sampleTsv, schemaVersion, sample);
    }
    byte[] sampleBytes = sampleTsv.toString().getBytes(StandardCharsets.UTF_8);
    byte[] resultBytes = jsonBytes(result(
        schemaVersion,
        runId,
        manifestBytes,
        sampleBytes,
        preparedWorkloads.workloads(),
        samples.size()));
    SchemaValidation resultValidation = validate(resultSchema, resultBytes);
    if (!resultValidation.valid()) {
      return invalid(resultValidation);
    }

    Path normalizedRoot = outputRoot.toAbsolutePath().normalize();
    Path claimDirectory = normalizedRoot.resolve(runId).normalize();
    if (!normalizedRoot.equals(claimDirectory.getParent())) {
      return invalid(new SchemaValidation(false, List.of("$: run path escapes output root")));
    }
    Path publishedDirectory = claimDirectory.resolve(PUBLISHED_DIRECTORY);
    Files.createDirectories(normalizedRoot);
    if (Files.exists(claimDirectory)) {
      return new ArtifactWriteResult(
          ArtifactWriteStatus.TARGET_EXISTS, publishedDirectory, null);
    }
    recoverOwnedStaging(normalizedRoot, runId, preparedWorkloads.workloads());
    return stageAndPublish(
        normalizedRoot,
        claimDirectory,
        runId,
        manifestBytes,
        sampleBytes,
        resultBytes,
        preparedWorkloads.workloads());
  }

  private ArtifactWriteResult stageAndPublish(
      Path outputRoot,
      Path claimDirectory,
      String runId,
      byte[] manifestBytes,
      byte[] sampleBytes,
      byte[] resultBytes,
      List<PreparedWorkload> workloads) throws IOException {
    Path staging = Files.createTempDirectory(outputRoot, ".pending-" + runId + '-');
    List<Path> stagedFiles = new ArrayList<>();
    byte[] claimMarker = claimMarkerBytes(runId);
    boolean claimOwned = false;
    try {
      Path ownerPath = staging.resolve(STAGING_MARKER);
      stagedFiles.add(ownerPath);
      writeNew(ownerPath, stagingMarkerBytes(runId));
      Path manifestPath = staging.resolve("manifest.json");
      stagedFiles.add(manifestPath);
      writeNew(manifestPath, manifestBytes);
      failAt(ArtifactWriteFailure.AFTER_FIRST_PAYLOAD);

      Path samplesPath = staging.resolve("samples.tsv");
      stagedFiles.add(samplesPath);
      writeNew(samplesPath, sampleBytes);
      for (PreparedWorkload workload : workloads) {
        Path path = staging.resolve(workload.fileName());
        stagedFiles.add(path);
        writeWorkloadNew(path, workload);
      }
      if (failure == ArtifactWriteFailure.CORRUPT_FIRST_WORKLOAD && !workloads.isEmpty()) {
        Files.writeString(
            staging.resolve(workloads.getFirst().fileName()),
            "injected-corruption",
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
      }
      verifyFile(manifestPath, WorkloadChecksums.sha256(manifestBytes));
      verifyFile(samplesPath, WorkloadChecksums.sha256(sampleBytes));
      for (PreparedWorkload workload : workloads) {
        verifyFile(staging.resolve(workload.fileName()), workload.sha256());
      }

      // Emit result.json last so the staged tree can be validated from its root document.
      Path resultPath = staging.resolve("result.json");
      stagedFiles.add(resultPath);
      writeNew(resultPath, resultBytes);
      verifyFile(resultPath, WorkloadChecksums.sha256(resultBytes));
      verifyResultReferences(staging, resultBytes);
      failAt(ArtifactWriteFailure.BEFORE_PUBLISH);
      publishProbe.beforeClaim(claimDirectory);
      try {
        Files.createDirectory(claimDirectory);
      } catch (FileAlreadyExistsException exception) {
        cleanupStaging(staging, stagedFiles);
        return new ArtifactWriteResult(
            ArtifactWriteStatus.TARGET_EXISTS,
            claimDirectory.resolve(PUBLISHED_DIRECTORY),
            null);
      }
      Path claimMarkerPath = claimDirectory.resolve(CLAIM_MARKER);
      writeNew(claimMarkerPath, claimMarker);
      claimOwned = true;
      Path publishedDirectory = claimDirectory.resolve(PUBLISHED_DIRECTORY);
      try {
        Files.move(staging, publishedDirectory, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException exception) {
        cleanupStaging(staging, stagedFiles);
        cleanupOwnedClaim(claimDirectory, claimMarker);
        return new ArtifactWriteResult(
            ArtifactWriteStatus.ATOMIC_PUBLISH_UNAVAILABLE, publishedDirectory, null);
      }
      return new ArtifactWriteResult(ArtifactWriteStatus.WRITTEN, publishedDirectory, null);
    } catch (IOException | RuntimeException exception) {
      try {
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
          cleanupStaging(staging, stagedFiles);
        }
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      if (claimOwned && Files.notExists(claimDirectory.resolve(PUBLISHED_DIRECTORY))) {
        try {
          cleanupOwnedClaim(claimDirectory, claimMarker);
        } catch (IOException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  private static PreparedWorkloads prepareWorkloads(List<WorkloadArtifact> workloads) {
    List<PreparedWorkload> prepared = new ArrayList<>(workloads.size());
    Set<String> fileNames = new HashSet<>();
    for (WorkloadArtifact workload : workloads) {
      byte[] bytes = workload.tsv();
      String actualDigest = WorkloadChecksums.sha256(bytes);
      if (!actualDigest.equals(workload.sha256())) {
        return new PreparedWorkloads(ArtifactWriteStatus.DIGEST_MISMATCH, List.of());
      }
      String fileName = workload.name() + "-v" + workload.version() + ".tsv";
      if (!fileNames.add(fileName)) {
        return new PreparedWorkloads(ArtifactWriteStatus.DUPLICATE_OUTPUT_NAME, List.of());
      }
      prepared.add(new PreparedWorkload(
          workload.name(),
          workload.version(),
          workload.seed(),
          workload.recordCount(),
          bytes.length,
          "partial_tiny_v1",
          workload.config(),
          fileName,
          output -> {
            output.write(bytes);
            return new ContentWrite(
                StreamingGenerationStatus.GENERATED,
                workload.recordCount(),
                bytes.length,
                actualDigest);
          },
          actualDigest));
    }
    return new PreparedWorkloads(ArtifactWriteStatus.WRITTEN, List.copyOf(prepared));
  }

  private static PreparedWorkloads prepareStreamingWorkloads(
      List<StreamingWorkloadArtifact> workloads) throws IOException {
    List<PreparedWorkload> prepared = new ArrayList<>(workloads.size());
    Set<String> fileNames = new HashSet<>();
    for (StreamingWorkloadArtifact workload : workloads) {
      String fileName = workload.name() + "-v" + workload.version() + ".tsv";
      if (!fileNames.add(fileName)) {
        return new PreparedWorkloads(ArtifactWriteStatus.DUPLICATE_OUTPUT_NAME, List.of());
      }
      MessageDigest digest = WorkloadChecksums.sha256Digest();
      StreamingGenerationResult generation;
      try (DigestOutputStream output = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
        generation = workload.writeTo(output, new byte[STREAMING_SCRATCH_BYTES]);
      }
      if (generation.status() != StreamingGenerationStatus.GENERATED
          || generation.rowCount() != workload.recordCount()) {
        return new PreparedWorkloads(
            ArtifactWriteStatus.WORKLOAD_GENERATION_FAILED, List.of());
      }
      String sha256 = WorkloadChecksums.hex(digest.digest());
      prepared.add(new PreparedWorkload(
          workload.name(),
          workload.version(),
          workload.seed(),
          workload.recordCount(),
          generation.byteCount(),
          workload.schemaId(),
          workload.config(),
          fileName,
          output -> {
            MessageDigest stagedDigest = WorkloadChecksums.sha256Digest();
            StreamingGenerationResult staged;
            try (DigestOutputStream digestOutput = new DigestOutputStream(
                new NonClosingOutputStream(output), stagedDigest)) {
              staged = workload.writeTo(
                  digestOutput, new byte[STREAMING_SCRATCH_BYTES]);
            }
            return new ContentWrite(
                staged.status(),
                staged.rowCount(),
                staged.byteCount(),
                WorkloadChecksums.hex(stagedDigest.digest()));
          },
          sha256));
    }
    return new PreparedWorkloads(ArtifactWriteStatus.WRITTEN, List.copyOf(prepared));
  }

  private static ObjectNode manifest(
      int schemaVersion,
      String runId,
      Instant createdAt,
      String riverCommit,
      List<PreparedWorkload> workloads,
      long highestTrackableNanos,
      int significantDigits) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schema_version", schemaVersion);
    root.put("artifact_type", "manifest");
    root.put("evidence_class", "local_smoke");
    root.put("run_id", runId);
    root.put("created_at", createdAt.toString());
    root.put("river_commit", riverCommit);
    ObjectNode environment = root.putObject("environment");
    environment.put("os", property("os.name") + " " + property("os.version"));
    environment.put("architecture", property("os.arch"));
    environment.put("java_runtime", property("java.runtime.version"));
    environment.put("java_vm", property("java.vm.name"));
    environment.put("available_processors", Runtime.getRuntime().availableProcessors());
    ArrayNode workloadArray = root.putArray("workloads");
    for (PreparedWorkload workload : workloads) {
      ObjectNode node = workloadArray.addObject();
      node.put("name", workload.name());
      node.put("version", workload.version());
      node.put("seed", workload.seed());
      node.put("record_count", workload.recordCount());
      if (schemaVersion >= 2) {
        node.put("byte_count", workload.byteCount());
        node.put("schema_id", workload.schemaId());
      }
      node.put("config", workload.config());
      node.put("sha256", workload.sha256());
    }
    ObjectNode measurement = root.putObject("measurement");
    measurement.put("clock", "synthetic_monotonic_smoke");
    measurement.put("highest_trackable_ns", highestTrackableNanos);
    measurement.put("significant_digits", significantDigits);
    measurement.putArray("modes").add("closed_loop").add("open_loop");
    ArrayNode gaps = root.putArray("canonical_gaps");
    gaps.add("reserved calibrated Linux runner and accepted control variance");
    gaps.add("approved Ingres comparison build on identical hardware");
    gaps.add("declared durable device filesystem and network calibration");
    gaps.add("repeated interleaved uninstrumented samples plus attributed profiles");
    gaps.add("reviewed numeric budgets and promotion decision");
    gaps.add("provenance clearance before any optional external dataset use");
    if (schemaVersion >= 2) {
      gaps.add("reviewed scale-to-hardware sizing and executable SQL workload drivers");
    } else {
      gaps.add("streaming canonical-scale workload generators and adapters");
    }
    return root;
  }

  private static ObjectNode result(
      int schemaVersion,
      String runId,
      byte[] manifest,
      byte[] samples,
      List<PreparedWorkload> workloads,
      int sampleCount) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schema_version", schemaVersion);
    root.put("artifact_type", "result");
    root.put("evidence_class", "local_smoke");
    root.put("run_id", runId);
    root.put("manifest_sha256", WorkloadChecksums.sha256(manifest));
    root.put("samples_sha256", WorkloadChecksums.sha256(samples));
    ArrayNode references = root.putArray("workload_artifacts");
    for (PreparedWorkload workload : workloads) {
      ObjectNode reference = references.addObject();
      reference.put("name", workload.name());
      reference.put("path", workload.fileName());
      if (schemaVersion >= 2) {
        reference.put("record_count", workload.recordCount());
        reference.put("byte_count", workload.byteCount());
      }
      reference.put("sha256", workload.sha256());
    }
    root.put("sample_count", sampleCount);
    root.put("status", "developer_smoke_not_promotion_evidence");
    return root;
  }

  private static ObjectNode sample(int schemaVersion, SampleArtifact sample) {
    LatencySnapshot latency = sample.latency();
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schema_version", schemaVersion);
    root.put("workload", sample.workload());
    root.put("mode", sample.mode());
    root.put("metric", sample.metric());
    root.put("operation_count", sample.operationCount());
    root.put("expected_interval_ns", sample.expectedIntervalNanos());
    root.put("histogram_count", latency.count());
    root.put("minimum_ns", latency.minimumNanos());
    root.put("p50_ns", latency.p50Nanos());
    root.put("p95_ns", latency.p95Nanos());
    root.put("p99_ns", latency.p99Nanos());
    root.put("p999_ns", latency.p999Nanos());
    root.put("maximum_ns", latency.maximumNanos());
    root.put("mean_ns", latency.meanNanos());
    return root;
  }

  private static void appendSample(
      StringBuilder output,
      int schemaVersion,
      SampleArtifact sample) {
    LatencySnapshot latency = sample.latency();
    output.append(schemaVersion).append('\t')
        .append(sample.workload()).append('\t')
        .append(sample.mode()).append('\t')
        .append(sample.metric()).append('\t')
        .append(sample.operationCount()).append('\t')
        .append(sample.expectedIntervalNanos()).append('\t')
        .append(latency.count()).append('\t')
        .append(latency.minimumNanos()).append('\t')
        .append(latency.p50Nanos()).append('\t')
        .append(latency.p95Nanos()).append('\t')
        .append(latency.p99Nanos()).append('\t')
        .append(latency.p999Nanos()).append('\t')
        .append(latency.maximumNanos()).append('\t')
        .append(latency.meanNanos()).append('\n');
  }

  private static ArtifactWriteResult invalid(SchemaValidation validation) {
    return new ArtifactWriteResult(ArtifactWriteStatus.INVALID_DOCUMENT, null, validation);
  }

  private SchemaValidation validate(String schema, byte[] bytes) {
    return validator.validate(schema, new String(bytes, StandardCharsets.UTF_8));
  }

  private void failAt(ArtifactWriteFailure point) throws IOException {
    if (failure == point) {
      throw new IOException("injected benchmark artifact failure at " + point);
    }
  }

  private static void verifyFile(Path path, String expectedSha256) throws IOException {
    MessageDigest digest = WorkloadChecksums.sha256Digest();
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[STREAMING_SCRATCH_BYTES];
      int length;
      while ((length = input.read(buffer)) >= 0) {
        if (length > 0) {
          digest.update(buffer, 0, length);
        }
      }
    }
    String actual = WorkloadChecksums.hex(digest.digest());
    if (!expectedSha256.equals(actual)) {
      throw new IOException("staged artifact digest mismatch: " + path.getFileName());
    }
  }

  private static void verifyResultReferences(Path staging, byte[] resultBytes)
      throws IOException {
    ObjectNode result = (ObjectNode) MAPPER.readTree(resultBytes);
    verifyFile(staging.resolve("manifest.json"), result.path("manifest_sha256").textValue());
    verifyFile(staging.resolve("samples.tsv"), result.path("samples_sha256").textValue());
    for (com.fasterxml.jackson.databind.JsonNode reference : result.path("workload_artifacts")) {
      Path path = staging.resolve(reference.path("path").textValue()).normalize();
      if (!staging.equals(path.getParent())) {
        throw new IOException("result reference escapes staging directory");
      }
      verifyFile(path, reference.path("sha256").textValue());
    }
  }

  private static void cleanupStaging(Path staging, List<Path> stagedFiles) throws IOException {
    IOException failure = null;
    for (int index = stagedFiles.size() - 1; index >= 0; index--) {
      Path file = stagedFiles.get(index);
      if (!staging.equals(file.getParent())) {
        throw new IOException("refusing to clean a path outside owned staging directory");
      }
      try {
        Files.deleteIfExists(file);
      } catch (IOException exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    try {
      Files.deleteIfExists(staging);
    } catch (IOException exception) {
      if (failure == null) {
        failure = exception;
      } else {
        failure.addSuppressed(exception);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static void recoverOwnedStaging(
      Path outputRoot,
      String runId,
      List<PreparedWorkload> workloads) throws IOException {
    String prefix = ".pending-" + runId + '-';
    Set<String> allowed = new HashSet<>(Set.of(
        STAGING_MARKER, "manifest.json", "samples.tsv", "result.json"));
    workloads.forEach(workload -> allowed.add(workload.fileName()));
    List<Path> candidates;
    try (Stream<Path> paths = Files.list(outputRoot)) {
      candidates = paths.filter(path -> path.getFileName().toString().startsWith(prefix))
          .toList();
    }
    byte[] expectedMarker = stagingMarkerBytes(runId);
    for (Path staging : candidates) {
      if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(staging)) {
        throw new IOException("refusing to recover non-directory staging path");
      }
      Path marker = staging.resolve(STAGING_MARKER);
      if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
          || !java.util.Arrays.equals(expectedMarker, Files.readAllBytes(marker))) {
        throw new IOException("refusing to recover staging without matching ownership marker");
      }
      List<Path> entries;
      try (Stream<Path> paths = Files.list(staging)) {
        entries = paths.toList();
      }
      for (Path entry : entries) {
        if (!staging.equals(entry.getParent())
            || !allowed.contains(entry.getFileName().toString())
            || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(entry)) {
          throw new IOException("refusing to recover staging with unexpected content");
        }
      }
      for (Path entry : entries) {
        Files.delete(entry);
      }
      Files.delete(staging);
    }
  }

  private static byte[] stagingMarkerBytes(String runId) {
    return ("river-bench-staging-v1\nrun_id=" + runId + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] claimMarkerBytes(String runId) {
    return ("river-bench-claim-v1\nrun_id=" + runId + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static void cleanupOwnedClaim(Path runDirectory, byte[] expectedMarker)
      throws IOException {
    Path marker = runDirectory.resolve(CLAIM_MARKER);
    if (!Files.isDirectory(runDirectory, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(runDirectory)
        || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(marker)
        || !java.util.Arrays.equals(expectedMarker, Files.readAllBytes(marker))) {
      throw new IOException("refusing to clean a run claim without matching ownership marker");
    }
    List<Path> entries;
    try (Stream<Path> paths = Files.list(runDirectory)) {
      entries = paths.toList();
    }
    if (entries.size() != 1 || !marker.equals(entries.getFirst())) {
      throw new IOException("refusing to clean a run claim with unexpected content");
    }
    Files.delete(marker);
    Files.delete(runDirectory);
  }

  private static String property(String name) {
    return System.getProperty(name, "unknown");
  }

  private static byte[] jsonBytes(ObjectNode node) throws JsonProcessingException {
    return (JSON.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  private static void writeNew(Path path, byte[] bytes) throws IOException {
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }

  private static void writeWorkloadNew(Path path, PreparedWorkload workload)
      throws IOException {
    ContentWrite content;
    try (OutputStream output = Files.newOutputStream(
        path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      content = workload.content().write(output);
    }
    if (content.status() != StreamingGenerationStatus.GENERATED
        || content.rowCount() != workload.recordCount()
        || content.byteCount() != workload.byteCount()
        || !content.sha256().equals(workload.sha256())) {
      throw new IOException("streamed workload changed between preflight and staging: "
          + workload.name());
    }
  }

  private record PreparedWorkloads(
      ArtifactWriteStatus status,
      List<PreparedWorkload> workloads) {
  }

  private record PreparedWorkload(
      String name,
      int version,
      long seed,
      long recordCount,
      long byteCount,
      String schemaId,
      String config,
      String fileName,
      WorkloadContent content,
      String sha256) {
  }

  @FunctionalInterface
  private interface WorkloadContent {
    ContentWrite write(OutputStream output) throws IOException;
  }

  private record ContentWrite(
      StreamingGenerationStatus status,
      long rowCount,
      long byteCount,
      String sha256) {
  }

  private static final class NonClosingOutputStream extends OutputStream {
    private final OutputStream output;

    private NonClosingOutputStream(OutputStream output) {
      this.output = output;
    }

    @Override
    public void write(int value) throws IOException {
      output.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      output.write(bytes, offset, length);
    }

    @Override
    public void flush() throws IOException {
      output.flush();
    }
  }
}
