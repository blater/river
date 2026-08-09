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
          "workload": "riverbank",
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
    assertFalse(validator.validate("future-schema.json", "{}").valid());
  }
}
