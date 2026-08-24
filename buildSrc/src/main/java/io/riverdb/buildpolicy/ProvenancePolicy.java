package io.riverdb.buildpolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Public façade for fail-closed validation of River's provenance ledger. */
public final class ProvenancePolicy {
  public static final String HEADER = String.join(",",
      "artifact_id", "artifact_type", "name", "upstream", "revision", "location",
      "sha256", "digest_algorithm", "license", "notice", "use", "vendoring", "approval");
  public static final String FILE_DIGEST = "sha256-file-v1";
  public static final String TREE_DIGEST = "river-tree-sha256-v2";
  public static final String REPOSITORY_DIGEST = "repository-head";

  private ProvenancePolicy() {
  }

  /** One fully validated ledger row. */
  public record Row(
      String artifactId,
      String artifactType,
      String name,
      String upstream,
      String revision,
      String location,
      String sha256,
      String digestAlgorithm,
      String license,
      String notice,
      String use,
      String vendoring,
      String approval,
      int lineNumber) {
  }

  /** A deterministic external-tree identity and its regular-file count. */
  public record TreeIdentity(String sha256, int fileCount) {
  }

  public static Map<String, Row> read(Path ledger) throws IOException {
    return parse(Files.readAllLines(ledger, StandardCharsets.UTF_8));
  }

  public static Map<String, Row> parse(List<String> lines) {
    return ProvenanceLedgerParser.parse(lines);
  }

  public static void verifyRepositoryNotices(Path repository, Iterable<Row> rows)
      throws IOException {
    ProvenanceEvidenceVerifier.verifyRepositoryNotices(repository, rows);
  }

  public static Map<String, TreeIdentity> verifyExternalReferences(
      Path workspaceRoot,
      Iterable<Row> rows) throws IOException {
    return ProvenanceEvidenceVerifier.verifyExternalReferences(workspaceRoot, rows);
  }

  public static void verifyResolvedDependencies(
      Iterable<Row> rows,
      Map<String, String> resolved) {
    ProvenanceDependencyVerifier.verify(rows, resolved);
  }

  public static void verifyGradleMetadata(
      Path metadata,
      Map<String, String> resolved) throws IOException {
    ProvenanceGradleMetadataVerifier.verify(metadata, resolved);
  }

  public static TreeIdentity treeIdentity(Path root) throws IOException {
    return ProvenanceTreeDigester.digest(root);
  }
}
