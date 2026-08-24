package io.riverdb.buildpolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the CSV-shaped provenance ledger into validated rows. */
final class ProvenanceLedgerParser {
  private ProvenanceLedgerParser() {
  }

  static Map<String, ProvenancePolicy.Row> parse(List<String> lines) {
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("provenance ledger is empty");
    }
    if (!ProvenancePolicy.HEADER.equals(lines.getFirst())) {
      throw new IllegalArgumentException("provenance ledger header does not match v2 schema");
    }
    Map<String, ProvenancePolicy.Row> rows = new LinkedHashMap<>();
    for (int index = 1; index < lines.size(); index++) {
      int lineNumber = index + 1;
      String line = lines.get(index);
      if (line.isBlank()) {
        throw new IllegalArgumentException("blank provenance ledger row at line " + lineNumber);
      }
      String[] fields = line.split(",", -1);
      if (fields.length != 13) {
        throw new IllegalArgumentException(
            "provenance ledger line " + lineNumber + " has " + fields.length
                + " fields, expected 13");
      }
      for (String field : fields) {
        if (!field.equals(field.strip())) {
          throw new IllegalArgumentException(
              "provenance ledger fields must not have outer whitespace at line " + lineNumber);
        }
      }
      ProvenancePolicy.Row row = new ProvenancePolicy.Row(
          fields[0], fields[1], fields[2], fields[3], fields[4], fields[5],
          fields[6], fields[7], fields[8], fields[9], fields[10], fields[11],
          fields[12], lineNumber);
      ProvenanceRowValidator.validate(row);
      if (rows.putIfAbsent(row.artifactId(), row) != null) {
        throw new IllegalArgumentException("duplicate provenance artifact ID " + row.artifactId());
      }
    }
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("provenance ledger has no artifact rows");
    }
    return Map.copyOf(rows);
  }

  static java.util.regex.Pattern externalLocationPattern() {
    return ProvenanceRowValidator.externalLocationPattern();
  }

  static java.util.regex.Pattern repositoryLocationPattern() {
    return ProvenanceRowValidator.repositoryLocationPattern();
  }

  static java.util.regex.Pattern sha256Pattern() {
    return ProvenanceRowValidator.sha256Pattern();
  }

  static IllegalArgumentException invalid(ProvenancePolicy.Row row, String detail) {
    return ProvenanceRowValidator.invalid(row, detail);
  }
}
