package io.riverdb.observability.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.observability.api.event.EventTypeId;
import io.riverdb.observability.api.event.Severity;
import io.riverdb.observability.api.metric.MetricName;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class RegistryCompatibilityFixtureTest {
  @Test
  void eventRegistryMatchesFrozenVersionOneFixture() throws IOException {
    StringBuilder actual = new StringBuilder();
    for (EventTypeId type : EventTypeId.values()) {
      actual.append(type.name()).append('\t')
          .append(type.stableId()).append('\t')
          .append(type.canonicalName()).append('\t')
          .append(type.schemaVersion());
      for (int fieldIndex = 0; fieldIndex < 4; fieldIndex++) {
        actual.append('\t').append(type.fieldSensitivity(fieldIndex));
      }
      actual.append('\n');
    }
    assertEquals(fixture("event-types-v1.tsv"), actual.toString());
  }

  @Test
  void metricRegistryMatchesFrozenVersionOneFixture() throws IOException {
    StringBuilder actual = new StringBuilder();
    for (MetricName metric : MetricName.values()) {
      actual.append(metric.name()).append('\t')
          .append(metric.stableId()).append('\t')
          .append(metric.canonicalName()).append('\t')
          .append(metric.kind()).append('\t')
          .append(metric.unit()).append('\t')
          .append(metric.isRetired() ? "RETIRED" : "ACTIVE").append('\n');
    }
    assertEquals(fixture("metric-names-v1.tsv"), actual.toString());
  }

  @Test
  void severityRegistryMatchesFrozenVersionOneFixture() throws IOException {
    StringBuilder actual = new StringBuilder();
    for (Severity severity : Severity.values()) {
      actual.append(severity.name()).append('\t')
          .append(severity.stableCode()).append('\n');
    }
    assertEquals(fixture("severities-v1.tsv"), actual.toString());
  }

  @Test
  void registryVersionOneBaselineIsFrozenAtHardeningCommit() throws IOException {
    String actual = "version\t" + ObservabilityRegistryV1.VERSION + '\n'
        + "baseline-commit\t" + ObservabilityRegistryV1.BASELINE_COMMIT + '\n';
    assertEquals(fixture("registry-v1.tsv"), actual);
    assertEquals(
        MetricName.RETIRED_DIAGNOSTIC_EVENTS_COALESCED_TOTAL,
        MetricName.fromStableId(1002));
  }

  private static String fixture(String name) throws IOException {
    String resource = "/io/riverdb/observability/api/registry/" + name;
    try (InputStream input = RegistryCompatibilityFixtureTest.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IOException("missing registry fixture " + resource);
      }
      return new String(input.readAllBytes(), UTF_8);
    }
  }
}
