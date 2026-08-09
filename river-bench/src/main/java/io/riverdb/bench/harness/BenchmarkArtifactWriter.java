package io.riverdb.bench.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/** Writes a self-checking, create-once local benchmark artifact directory. */
public final class BenchmarkArtifactWriter {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ObjectWriter JSON = MAPPER.writerWithDefaultPrettyPrinter();
  private static final String SAMPLE_HEADER = String.join("\t",
      "schema_version", "workload", "mode", "metric", "operation_count",
      "expected_interval_ns", "histogram_count", "minimum_ns", "p50_ns",
      "p95_ns", "p99_ns", "p999_ns", "maximum_ns", "mean_ns") + "\n";

  private final BenchmarkSchemaValidator validator = new BenchmarkSchemaValidator();

  public ArtifactWriteResult write(
      Path outputRoot,
      String runId,
      Instant createdAt,
      String riverCommit,
      List<WorkloadArtifact> workloads,
      List<SampleArtifact> samples,
      long highestTrackableNanos,
      int significantDigits) throws IOException {
    ObjectNode manifest = manifest(
        runId,
        createdAt,
        riverCommit,
        workloads,
        highestTrackableNanos,
        significantDigits);
    byte[] manifestBytes = jsonBytes(manifest);
    SchemaValidation manifestValidation = validator.validate(
        BenchmarkSchemaValidator.MANIFEST,
        new String(manifestBytes, StandardCharsets.UTF_8));
    if (!manifestValidation.valid()) {
      return new ArtifactWriteResult(
          ArtifactWriteStatus.INVALID_DOCUMENT, null, manifestValidation);
    }

    StringBuilder sampleTsv = new StringBuilder(SAMPLE_HEADER);
    for (SampleArtifact sample : samples) {
      ObjectNode sampleNode = sample(sample);
      String sampleJson = MAPPER.writeValueAsString(sampleNode);
      SchemaValidation sampleValidation = validator.validate(
          BenchmarkSchemaValidator.SAMPLE, sampleJson);
      if (!sampleValidation.valid()) {
        return new ArtifactWriteResult(
            ArtifactWriteStatus.INVALID_DOCUMENT, null, sampleValidation);
      }
      appendSample(sampleTsv, sample);
    }
    byte[] sampleBytes = sampleTsv.toString().getBytes(StandardCharsets.UTF_8);
    ObjectNode result = result(runId, manifestBytes, sampleBytes, workloads, samples.size());
    byte[] resultBytes = jsonBytes(result);
    SchemaValidation resultValidation = validator.validate(
        BenchmarkSchemaValidator.RESULT,
        new String(resultBytes, StandardCharsets.UTF_8));
    if (!resultValidation.valid()) {
      return new ArtifactWriteResult(
          ArtifactWriteStatus.INVALID_DOCUMENT, null, resultValidation);
    }

    Path runDirectory = outputRoot.resolve(runId);
    try {
      Files.createDirectories(outputRoot);
      Files.createDirectory(runDirectory);
    } catch (FileAlreadyExistsException exception) {
      return new ArtifactWriteResult(ArtifactWriteStatus.TARGET_EXISTS, runDirectory, null);
    }
    writeNew(runDirectory.resolve("manifest.json"), manifestBytes);
    writeNew(runDirectory.resolve("samples.tsv"), sampleBytes);
    writeNew(runDirectory.resolve("result.json"), resultBytes);
    for (WorkloadArtifact workload : workloads) {
      writeNew(runDirectory.resolve(workload.name() + "-v" + workload.version() + ".tsv"),
          workload.tsv());
    }
    return new ArtifactWriteResult(ArtifactWriteStatus.WRITTEN, runDirectory, null);
  }

  private static ObjectNode manifest(
      String runId,
      Instant createdAt,
      String riverCommit,
      List<WorkloadArtifact> workloads,
      long highestTrackableNanos,
      int significantDigits) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schema_version", 1);
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
    for (WorkloadArtifact workload : workloads) {
      ObjectNode node = workloadArray.addObject();
      node.put("name", workload.name());
      node.put("version", workload.version());
      node.put("seed", workload.seed());
      node.put("record_count", workload.recordCount());
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
    return root;
  }

  private static ObjectNode result(
      String runId,
      byte[] manifest,
      byte[] samples,
      List<WorkloadArtifact> workloads,
      int sampleCount) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schema_version", 1);
    root.put("artifact_type", "result");
    root.put("evidence_class", "local_smoke");
    root.put("run_id", runId);
    root.put("manifest_sha256", WorkloadChecksums.sha256(manifest));
    root.put("samples_sha256", WorkloadChecksums.sha256(samples));
    ArrayNode checksums = root.putArray("workload_sha256");
    workloads.forEach(workload -> checksums.add(workload.sha256()));
    root.put("sample_count", sampleCount);
    root.put("status", "developer_smoke_not_promotion_evidence");
    return root;
  }

  private static ObjectNode sample(SampleArtifact sample) {
    LatencySnapshot latency = sample.latency();
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schema_version", 1);
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

  private static void appendSample(StringBuilder output, SampleArtifact sample) {
    LatencySnapshot latency = sample.latency();
    output.append(1).append('\t')
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

  private static String property(String name) {
    return System.getProperty(name, "unknown");
  }

  private static byte[] jsonBytes(ObjectNode node) throws JsonProcessingException {
    return (JSON.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  private static void writeNew(Path path, byte[] bytes) throws IOException {
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }
}
