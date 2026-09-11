package io.riverdb.bench.harness;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks relationships between fields after an artifact passes its schema tree. */
final class BenchmarkArtifactSemantics {
  private BenchmarkArtifactSemantics() {}

  static void validate(String schemaName, JsonNode document, List<String> errors) {
    if ((BenchmarkSchemaValidator.SAMPLE.equals(schemaName)
            || BenchmarkSchemaValidator.STREAMING_SAMPLE.equals(schemaName))
        && document.isObject()) {
      validateSampleSemantics(document, errors);
    } else if (BenchmarkSchemaValidator.STREAMING_MANIFEST.equals(schemaName)
        && document.isObject()) {
      BenchmarkStreamingManifestSemantics.validate(document, errors);
    } else if ((BenchmarkSchemaValidator.RESULT.equals(schemaName)
            || BenchmarkSchemaValidator.STREAMING_RESULT.equals(schemaName))
        && document.isObject()) {
      validateResultSemantics(document, errors);
    }
  }

  private static void validateSampleSemantics(JsonNode sample, List<String> errors) {
    String mode = sample.path("mode").textValue();
    String metric = sample.path("metric").textValue();
    long operations = sample.path("operation_count").asLong(-1);
    long interval = sample.path("expected_interval_ns").asLong(-1);
    long histogramCount = sample.path("histogram_count").asLong(-1);
    if ("closed_loop".equals(mode)) {
      if (!"service".equals(metric)) {
        errors.add("$.metric: closed_loop only permits service");
      }
      if (interval != 0) {
        errors.add("$.expected_interval_ns: closed_loop requires zero");
      }
    } else if ("open_loop".equals(mode) && interval < 1) {
      errors.add("$.expected_interval_ns: open_loop requires a positive interval");
    }
    if ("coordinated_omission_corrected_service".equals(metric)) {
      if (histogramCount < operations) {
        errors.add("$.histogram_count: corrected count cannot be below operation count");
      }
    } else if (histogramCount != operations) {
      errors.add("$.histogram_count: service/scheduled count must equal operation count");
    }
    long minimum = sample.path("minimum_ns").asLong(-1);
    long p50 = sample.path("p50_ns").asLong(-1);
    long p95 = sample.path("p95_ns").asLong(-1);
    long p99 = sample.path("p99_ns").asLong(-1);
    long p999 = sample.path("p999_ns").asLong(-1);
    long maximum = sample.path("maximum_ns").asLong(-1);
    if (!(minimum <= p50 && p50 <= p95 && p95 <= p99
        && p99 <= p999 && p999 <= maximum)) {
      errors.add("$: latency quantiles must be monotonic from minimum through maximum");
    }
    double mean = sample.path("mean_ns").asDouble(Double.NaN);
    if (!Double.isFinite(mean) || mean < minimum || mean > maximum) {
      errors.add("$.mean_ns: mean must be finite and within minimum/maximum");
    }
  }

  private static void validateResultSemantics(JsonNode result, List<String> errors) {
    Set<String> paths = new HashSet<>();
    Set<String> names = new HashSet<>();
    for (JsonNode reference : result.path("workload_artifacts")) {
      String name = reference.path("name").textValue();
      String path = reference.path("path").textValue();
      if (name != null && !names.add(name)) {
        errors.add("$.workload_artifacts: duplicate workload name " + name);
      }
      if (name != null && path != null && !path.startsWith(name + "-v")) {
        errors.add("$.workload_artifacts: path does not identify its named workload");
      }
      if (path != null && !paths.add(path)) {
        errors.add("$.workload_artifacts: duplicate output path " + path);
      }
    }
  }


}
