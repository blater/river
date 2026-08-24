package io.riverdb.buildpolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compares declared dependency provenance with the build's resolved artifacts. */
final class ProvenanceDependencyVerifier {
  private ProvenanceDependencyVerifier() {
  }

  static void verify(
      Iterable<ProvenancePolicy.Row> rows,
      Map<String, String> resolved) {
    Map<String, ProvenancePolicy.Row> declared = new LinkedHashMap<>();
    for (ProvenancePolicy.Row row : rows) {
      if (!"dependency".equals(row.artifactType())) {
        continue;
      }
      if (row.name().chars().filter(value -> value == ':').count() != 1) {
        throw ProvenanceLedgerParser.invalid(row, "dependency coordinate must be group:name");
      }
      String coordinate = row.name() + ":" + row.revision();
      if (declared.putIfAbsent(coordinate, row) != null) {
        throw ProvenanceLedgerParser.invalid(row, "duplicate dependency coordinate " + coordinate);
      }
    }
    List<String> missing = resolved.keySet().stream()
        .filter(key -> !declared.containsKey(key))
        .sorted()
        .toList();
    List<String> stale = declared.keySet().stream()
        .filter(key -> !resolved.containsKey(key))
        .sorted()
        .toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "resolved dependencies missing from provenance ledger: " + missing);
    }
    if (!stale.isEmpty()) {
      throw new IllegalArgumentException(
          "provenance dependency rows are not resolved by the build: " + stale);
    }
    resolved.forEach((coordinate, actual) -> {
      ProvenancePolicy.Row row = declared.get(coordinate);
      if (!row.sha256().equals(actual)) {
        throw ProvenanceLedgerParser.invalid(
            row,
            "dependency checksum mismatch for " + coordinate
                + ": expected " + row.sha256() + ", got " + actual);
      }
    });
  }
}
