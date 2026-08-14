package io.riverdb.bench.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Prepares validated, immutable-after-build publication documents and content producers. */
final class BenchmarkArtifactDocuments {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ObjectWriter JSON = MAPPER.writerWithDefaultPrettyPrinter();
  private static final String SAMPLE_HEADER = String.join("\t",
      "schema_version", "workload", "mode", "metric", "operation_count",
      "expected_interval_ns", "histogram_count", "minimum_ns", "p50_ns",
      "p95_ns", "p99_ns", "p999_ns", "maximum_ns", "mean_ns") + "\n";
  private static final int STREAMING_SCRATCH_BYTES = 64 * 1024;

  private final BenchmarkSchemaValidator validator = new BenchmarkSchemaValidator();

  PublicationPlan prepareBuffered(
      String runId,
      Instant createdAt,
      String riverCommit,
      List<WorkloadArtifact> workloads,
      List<SampleArtifact> samples,
      long highestTrackableNanos,
      int significantDigits) throws IOException {
    return prepare(
        runId,
        createdAt,
        riverCommit,
        prepareWorkloads(workloads),
        samples,
        highestTrackableNanos,
        significantDigits,
        1,
        BenchmarkSchemaValidator.MANIFEST,
        BenchmarkSchemaValidator.RESULT,
        BenchmarkSchemaValidator.SAMPLE);
  }

  PublicationPlan prepareStreaming(
      String runId,
      Instant createdAt,
      String riverCommit,
      List<StreamingWorkloadArtifact> workloads,
      List<SampleArtifact> samples,
      long highestTrackableNanos,
      int significantDigits) throws IOException {
    return prepare(
        runId,
        createdAt,
        riverCommit,
        prepareStreamingWorkloads(workloads),
        samples,
        highestTrackableNanos,
        significantDigits,
        2,
        BenchmarkSchemaValidator.STREAMING_MANIFEST,
        BenchmarkSchemaValidator.STREAMING_RESULT,
        BenchmarkSchemaValidator.STREAMING_SAMPLE);
  }

  ObjectNode readResult(byte[] resultBytes) throws IOException {
    return (ObjectNode) MAPPER.readTree(resultBytes);
  }

  private PublicationPlan prepare(
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
      return PublicationPlan.failed(preparedWorkloads.status());
    }
    byte[] manifestBytes = jsonBytes(manifest(
        schemaVersion,
        runId,
        createdAt,
        riverCommit,
        preparedWorkloads.workloads(),
        highestTrackableNanos,
        significantDigits));
    SchemaValidation manifestValidation = validate(manifestSchema, manifestBytes);
    if (!manifestValidation.valid()) {
      return PublicationPlan.invalid(manifestValidation);
    }

    StringBuilder sampleTsv = new StringBuilder(SAMPLE_HEADER);
    Set<String> workloadNames = new HashSet<>();
    preparedWorkloads.workloads().forEach(workload -> workloadNames.add(workload.name()));
    for (SampleArtifact sample : samples) {
      if (!workloadNames.contains(sample.workload())) {
        return PublicationPlan.invalid(new SchemaValidation(
            false, List.of("$.workload: sample references an absent workload")));
      }
      SchemaValidation sampleValidation = validator.validate(
          sampleSchema, MAPPER.writeValueAsString(sample(schemaVersion, sample)));
      if (!sampleValidation.valid()) {
        return PublicationPlan.invalid(sampleValidation);
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
      return PublicationPlan.invalid(resultValidation);
    }
    return PublicationPlan.ready(
        manifestBytes, sampleBytes, resultBytes, preparedWorkloads.workloads());
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
      CountingOutputStream counted = new CountingOutputStream(OutputStream.nullOutputStream());
      StreamingGenerationResult generation;
      try (DigestOutputStream output = new DigestOutputStream(counted, digest)) {
        generation = workload.writeTo(output, new byte[STREAMING_SCRATCH_BYTES]);
      }
      long observedRows = counted.dataRows();
      if (generation.status() != StreamingGenerationStatus.GENERATED
          || generation.rowCount() != workload.recordCount()
          || generation.rowCount() != observedRows
          || generation.byteCount() != counted.byteCount()) {
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
            CountingOutputStream stagedCount = new CountingOutputStream(
                new NonClosingOutputStream(output));
            StreamingGenerationResult staged;
            try (DigestOutputStream digestOutput = new DigestOutputStream(
                stagedCount, stagedDigest)) {
              staged = workload.writeTo(
                  digestOutput, new byte[STREAMING_SCRATCH_BYTES]);
            }
            StreamingGenerationStatus status = staged.status();
            if (status == StreamingGenerationStatus.GENERATED
                && staged.rowCount() != stagedCount.dataRows()) {
              status = StreamingGenerationStatus.ROW_COUNT_MISMATCH;
            }
            if (status == StreamingGenerationStatus.GENERATED
                && staged.byteCount() != stagedCount.byteCount()) {
              status = StreamingGenerationStatus.BYTE_COUNT_MISMATCH;
            }
            return new ContentWrite(
                status,
                stagedCount.dataRows(),
                stagedCount.byteCount(),
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
      gaps.add("full canonical RiverBank tables operations mutations and expected aggregates");
      gaps.add("RiverPapers revision histories and executable index query corpus");
      gaps.add("dedicated allocation and generated-hot-path bytecode evidence");
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

  private SchemaValidation validate(String schema, byte[] bytes) {
    return validator.validate(schema, new String(bytes, StandardCharsets.UTF_8));
  }

  private static String property(String name) {
    return System.getProperty(name, "unknown");
  }

  private static byte[] jsonBytes(ObjectNode node) throws JsonProcessingException {
    return (JSON.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** Byte arrays are plan-owned, immutable after construction, and borrowed only synchronously. */
  record PublicationPlan(
      ArtifactWriteStatus status,
      SchemaValidation validation,
      byte[] manifestBytes,
      byte[] sampleBytes,
      byte[] resultBytes,
      List<PreparedWorkload> workloads) {
    static PublicationPlan ready(
        byte[] manifestBytes,
        byte[] sampleBytes,
        byte[] resultBytes,
        List<PreparedWorkload> workloads) {
      return new PublicationPlan(
          ArtifactWriteStatus.WRITTEN,
          null,
          manifestBytes,
          sampleBytes,
          resultBytes,
          workloads);
    }

    static PublicationPlan failed(ArtifactWriteStatus status) {
      return new PublicationPlan(status, null, null, null, null, List.of());
    }

    static PublicationPlan invalid(SchemaValidation validation) {
      return new PublicationPlan(
          ArtifactWriteStatus.INVALID_DOCUMENT,
          validation,
          null,
          null,
          null,
          List.of());
    }
  }

  private record PreparedWorkloads(
      ArtifactWriteStatus status,
      List<PreparedWorkload> workloads) {
  }

  record PreparedWorkload(
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
  interface WorkloadContent {
    ContentWrite write(OutputStream output) throws IOException;
  }

  record ContentWrite(
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

  private static final class CountingOutputStream extends OutputStream {
    private final OutputStream output;
    private long byteCount;
    private long lineFeeds;

    private CountingOutputStream(OutputStream output) {
      this.output = output;
    }

    @Override
    public void write(int value) throws IOException {
      output.write(value);
      byteCount = Math.addExact(byteCount, 1);
      if ((value & 0xff) == '\n') {
        lineFeeds = Math.addExact(lineFeeds, 1);
      }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      output.write(bytes, offset, length);
      byteCount = Math.addExact(byteCount, length);
      for (int index = offset; index < offset + length; index++) {
        if (bytes[index] == '\n') {
          lineFeeds = Math.addExact(lineFeeds, 1);
        }
      }
    }

    @Override
    public void flush() throws IOException {
      output.flush();
    }

    long byteCount() {
      return byteCount;
    }

    long dataRows() {
      return lineFeeds < 1 ? -1 : lineFeeds - 1;
    }
  }
}
