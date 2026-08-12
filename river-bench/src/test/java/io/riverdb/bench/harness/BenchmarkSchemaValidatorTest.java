package io.riverdb.bench.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BenchmarkSchemaValidatorTest {
  private final BenchmarkSchemaValidator validator = new BenchmarkSchemaValidator();

  @Test
  void acceptsSchemaConformingSample() {
    SchemaValidation validation = validator.validate(BenchmarkSchemaValidator.SAMPLE, """
        {
          "schema_version": 1,
          "workload": "riverbank_tiny",
          "mode": "open_loop",
          "metric": "scheduled",
          "operation_count": 10,
          "expected_interval_ns": 1000,
          "histogram_count": 10,
          "minimum_ns": 10,
          "p50_ns": 20,
          "p95_ns": 30,
          "p99_ns": 40,
          "p999_ns": 50,
          "maximum_ns": 60,
          "mean_ns": 25.5
        }
        """);

    assertTrue(validation.valid(), validation.errors().toString());
  }

  @Test
  void rejectsMissingUnknownAndOutOfBoundsFields() {
    SchemaValidation validation = validator.validate(BenchmarkSchemaValidator.SAMPLE, """
        {
          "schema_version": 1,
          "workload": "not-river-owned",
          "mode": "open_loop",
          "metric": "scheduled",
          "operation_count": -1,
          "expected_interval_ns": 1000,
          "histogram_count": 1,
          "minimum_ns": 0,
          "p50_ns": 0,
          "p95_ns": 0,
          "p99_ns": 0,
          "p999_ns": 0,
          "maximum_ns": 0,
          "unexpected": true
        }
        """);

    assertFalse(validation.valid());
    assertTrue(validation.errors().size() >= 4, validation.errors().toString());
  }

  @Test
  void rejectsMalformedJsonAndUnknownSchema() {
    assertFalse(validator.validate(BenchmarkSchemaValidator.MANIFEST, "{").valid());
    assertFalse(validator.validate(BenchmarkSchemaValidator.SAMPLE, "{} {}").valid());
    assertFalse(validator.validate(BenchmarkSchemaValidator.SAMPLE,
        "{\"mode\":\"open_loop\",\"mode\":\"closed_loop\"}").valid());
    assertFalse(validator.validate("future-schema.json", "{}").valid());
  }

  @Test
  void rejectsSemanticallyInconsistentLatencySample() {
    SchemaValidation validation = validator.validate(BenchmarkSchemaValidator.SAMPLE, """
        {
          "schema_version": 1,
          "workload": "riverbank_tiny",
          "mode": "closed_loop",
          "metric": "scheduled",
          "operation_count": 10,
          "expected_interval_ns": 1000,
          "histogram_count": 9,
          "minimum_ns": 10,
          "p50_ns": 30,
          "p95_ns": 20,
          "p99_ns": 40,
          "p999_ns": 50,
          "maximum_ns": 60,
          "mean_ns": 70
        }
        """);

    assertFalse(validation.valid());
    assertTrue(validation.errors().size() >= 5, validation.errors().toString());
  }

  @Test
  void validatesStreamingManifestIdentitySemanticsWithoutChangingV1() {
    String valid = validStreamingManifest();

    assertTrue(validator.validate(BenchmarkSchemaValidator.STREAMING_MANIFEST, valid).valid());
    assertFalse(validator.validate(BenchmarkSchemaValidator.STREAMING_MANIFEST,
        valid.replace("riverbank_transactions\"", "riverbank_accounts\"")).valid());
    assertFalse(validator.validate(BenchmarkSchemaValidator.STREAMING_MANIFEST,
        valid.replace("riverbank.transactions.v2", "riverpapers.transactions.v2")).valid());
    assertFalse(validator.validate(BenchmarkSchemaValidator.STREAMING_MANIFEST,
        valid.replace("table=transactions", "table=accounts")).valid());
    assertFalse(validator.validate(BenchmarkSchemaValidator.STREAMING_MANIFEST,
        valid.replace(
            "\"name\": \"riverbank_transactions\", \"version\": 2, \"seed\": 42",
            "\"name\": \"riverbank_transactions\", \"version\": 2, \"seed\": 43"))
        .valid());
    assertFalse(validator.validate(BenchmarkSchemaValidator.MANIFEST,
        valid.replace("\"schema_version\": 2", "\"schema_version\": 1")).valid());
  }

  @Test
  void rejectsDuplicateStreamingResultWorkloadIdentity() {
    SchemaValidation validation = validator.validate(
        BenchmarkSchemaValidator.STREAMING_RESULT, """
        {
          "schema_version": 2,
          "artifact_type": "result",
          "evidence_class": "local_smoke",
          "run_id": "local-streaming-result",
          "manifest_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "samples_sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
          "workload_artifacts": [
            {
              "name": "riverbank_accounts", "path": "riverbank_accounts-v2.tsv",
              "record_count": 1, "byte_count": 10,
              "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
            },
            {
              "name": "riverbank_accounts", "path": "riverbank_accounts-v2.tsv",
              "record_count": 1, "byte_count": 10,
              "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
            }
          ],
          "sample_count": 1,
          "status": "developer_smoke_not_promotion_evidence"
        }
        """);

    assertFalse(validation.valid());
    assertTrue(validation.errors().stream()
        .anyMatch(error -> error.contains("duplicate workload name")));
  }

  private static String validStreamingManifest() {
    return """
        {
          "schema_version": 2,
          "artifact_type": "manifest",
          "evidence_class": "local_smoke",
          "run_id": "local-streaming-test",
          "created_at": "2026-08-09T14:22:00Z",
          "river_commit": "test",
          "environment": {
            "os": "test", "architecture": "test", "java_runtime": "25",
            "java_vm": "test", "available_processors": 1
          },
          "workloads": [
            {
              "name": "riverbank_accounts", "version": 2, "seed": 42,
              "record_count": 10, "byte_count": 100,
              "schema_id": "riverbank.accounts.v2",
              "config": "schema=riverbank_v2;scale=test;table=accounts",
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            },
            {
              "name": "riverbank_transactions", "version": 2, "seed": 42,
              "record_count": 20, "byte_count": 200,
              "schema_id": "riverbank.transactions.v2",
              "config": "schema=riverbank_v2;scale=test;table=transactions",
              "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            }
          ],
          "measurement": {
            "clock": "synthetic_monotonic_smoke", "highest_trackable_ns": 1000,
            "significant_digits": 3, "modes": ["closed_loop", "open_loop"]
          },
          "canonical_gaps": ["not promotion evidence"]
        }
        """;
  }
}
