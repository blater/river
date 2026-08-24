package io.riverdb.buildpolicy;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Enforces field-level and artifact-type-specific provenance invariants. */
final class ProvenanceRowValidator {
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");
  private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern APPROVAL = Pattern.compile(
      "approved:(\\d{4}-\\d{2}-\\d{2}):project-owner-decision");
  private static final Pattern EXTERNAL_LOCATION = Pattern.compile(
      "external-workspace:([A-Za-z0-9][A-Za-z0-9._-]*)");
  private static final Pattern REPOSITORY_LOCATION = Pattern.compile(
      "repository:(\\.|[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*)");
  private static final Pattern NOTICE = Pattern.compile(
      "(repository-file|artifact-file|external-file):"
          + "([A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*)|not-vendored");
  private static final Set<String> TYPES = Set.of(
      "source", "tool", "dependency", "reference", "dataset");

  private ProvenanceRowValidator() {
  }

  static void validate(ProvenancePolicy.Row row) {
    if (!ID.matcher(row.artifactId()).matches()) {
      throw invalid(row, "artifact ID is malformed");
    }
    if (!TYPES.contains(row.artifactType())) {
      throw invalid(row, "artifact type is unsupported");
    }
    requirePresent(row.name(), "name", row);
    validateUpstream(row);
    if (!REVISION.matcher(row.revision()).matches()) {
      throw invalid(row, "revision is malformed");
    }
    requirePresent(row.license(), "license", row);
    requirePresent(row.use(), "use", row);
    requirePresent(row.vendoring(), "vendoring", row);
    rejectUnresolved(row.license(), "license", row);
    rejectUnresolved(row.notice(), "notice", row);
    rejectUnresolved(row.approval(), "approval", row);
    Matcher approval = APPROVAL.matcher(row.approval());
    if (!approval.matches()) {
      throw invalid(row, "approval must match approved:YYYY-MM-DD:<authority>-decision");
    }
    try {
      LocalDate.parse(approval.group(1));
    } catch (DateTimeParseException failure) {
      throw invalid(row, "approval date is invalid");
    }
    if (!NOTICE.matcher(row.notice()).matches()) {
      throw invalid(row, "notice outcome is malformed");
    }
    switch (row.artifactType()) {
      case "source" -> validateSource(row);
      case "dependency" -> validateDependency(row);
      case "tool" -> validateTool(row);
      case "reference", "dataset" -> validateExternalTree(row);
      default -> throw invalid(row, "artifact type is unsupported");
    }
  }

  private static void validateSource(ProvenancePolicy.Row row) {
    if (!"repository:.".equals(row.location())
        || !"none".equals(row.sha256())
        || !ProvenancePolicy.REPOSITORY_DIGEST.equals(row.digestAlgorithm())
        || !row.notice().startsWith("repository-file:")) {
      throw invalid(row, "source row must identify the live repository and its notice file");
    }
  }

  private static void validateUpstream(ProvenancePolicy.Row row) {
    try {
      URI upstream = new URI(row.upstream());
      if (!("https".equals(upstream.getScheme()) || "http".equals(upstream.getScheme()))
          || !upstream.isAbsolute()
          || upstream.getHost() == null
          || upstream.getHost().isBlank()
          || upstream.getUserInfo() != null
          || upstream.getQuery() != null
          || upstream.getFragment() != null) {
        throw invalid(row, "upstream must be an absolute HTTP(S) source URL");
      }
    } catch (URISyntaxException failure) {
      throw invalid(row, "upstream must be an absolute HTTP(S) source URL");
    }
  }

  private static void validateDependency(ProvenancePolicy.Row row) {
    if (!"gradle-cache".equals(row.location())
        || !SHA256.matcher(row.sha256()).matches()
        || !ProvenancePolicy.FILE_DIGEST.equals(row.digestAlgorithm())
        || !"not-vendored".equals(row.notice())) {
      throw invalid(row, "dependency row fields are inconsistent");
    }
  }

  private static void validateTool(ProvenancePolicy.Row row) {
    boolean wrapperCache = "wrapper-cache".equals(row.location());
    boolean repositoryArtifact = REPOSITORY_LOCATION.matcher(row.location()).matches()
        && !"repository:.".equals(row.location());
    if ((!wrapperCache && !repositoryArtifact)
        || !SHA256.matcher(row.sha256()).matches()
        || !ProvenancePolicy.FILE_DIGEST.equals(row.digestAlgorithm())) {
      throw invalid(row, "tool row fields are inconsistent");
    }
    if (repositoryArtifact && !row.notice().startsWith("artifact-file:")) {
      throw invalid(row, "repository tool must identify embedded notice evidence");
    }
    if (wrapperCache && !"not-vendored".equals(row.notice())) {
      throw invalid(row, "cached tool must use the not-vendored notice outcome");
    }
  }

  private static void validateExternalTree(ProvenancePolicy.Row row) {
    if (!EXTERNAL_LOCATION.matcher(row.location()).matches()
        || !SHA256.matcher(row.sha256()).matches()
        || !ProvenancePolicy.TREE_DIGEST.equals(row.digestAlgorithm())
        || !row.notice().startsWith("external-file:")) {
      throw invalid(row, "external reference fields are inconsistent");
    }
  }

  private static void rejectUnresolved(String value, String field, ProvenancePolicy.Row row) {
    String normalized = value.toLowerCase(java.util.Locale.ROOT);
    if (normalized.contains("pending") || normalized.contains("unknown")
        || normalized.contains("tbd")) {
      throw invalid(row, field + " is unresolved");
    }
  }

  private static void requirePresent(String value, String field, ProvenancePolicy.Row row) {
    if (value.isBlank()) {
      throw invalid(row, field + " is missing");
    }
  }

  static Pattern externalLocationPattern() {
    return EXTERNAL_LOCATION;
  }

  static Pattern repositoryLocationPattern() {
    return REPOSITORY_LOCATION;
  }

  static Pattern sha256Pattern() {
    return SHA256;
  }

  static IllegalArgumentException invalid(ProvenancePolicy.Row row, String detail) {
    return new IllegalArgumentException("provenance row " + row.artifactId()
        + " at line " + row.lineNumber() + ": " + detail);
  }
}
