package io.riverdb.buildpolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.zip.ZipFile;

/** Verifies repository notices and approved external reference snapshots. */
final class ProvenanceEvidenceVerifier {
  private ProvenanceEvidenceVerifier() {
  }

  static void verifyRepositoryNotices(
      Path repository,
      Iterable<ProvenancePolicy.Row> rows) throws IOException {
    Path normalizedRepository = repository.toAbsolutePath().normalize();
    for (ProvenancePolicy.Row row : rows) {
      if (row.notice().startsWith("repository-file:")) {
        Path notice = repositoryPath(
            normalizedRepository,
            row.notice().substring("repository-file:".length()),
            row);
        requireRegularFile(notice, "repository notice", row);
      } else if (row.notice().startsWith("artifact-file:")) {
        Matcher location = ProvenanceLedgerParser.repositoryLocationPattern()
            .matcher(row.location());
        if (!location.matches() || ".".equals(location.group(1))) {
          throw ProvenanceLedgerParser.invalid(row, "artifact-file notice requires a repository artifact");
        }
        Path artifact = repositoryPath(normalizedRepository, location.group(1), row);
        requireRegularFile(artifact, "repository artifact", row);
        String entry = row.notice().substring("artifact-file:".length());
        try (ZipFile zip = new ZipFile(artifact.toFile())) {
          if (zip.getEntry(entry) == null) {
            throw ProvenanceLedgerParser.invalid(row, "artifact notice entry is missing: " + entry);
          }
        }
      }
    }
  }

  static Map<String, ProvenancePolicy.TreeIdentity> verifyExternalReferences(
      Path workspaceRoot,
      Iterable<ProvenancePolicy.Row> rows) throws IOException {
    Path normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize();
    Map<String, ProvenancePolicy.TreeIdentity> identities = new LinkedHashMap<>();
    for (ProvenancePolicy.Row row : rows) {
      if (!"reference".equals(row.artifactType())) {
        continue;
      }
      Matcher location = ProvenanceLedgerParser.externalLocationPattern()
          .matcher(row.location());
      if (!location.matches()) {
        throw ProvenanceLedgerParser.invalid(row, "reference location is malformed");
      }
      Path tree = normalizedWorkspace.resolve(location.group(1)).normalize();
      if (!tree.getParent().equals(normalizedWorkspace)) {
        throw ProvenanceLedgerParser.invalid(row, "reference location escapes the workspace root");
      }
      if (!Files.isDirectory(tree) || Files.isSymbolicLink(tree)) {
        throw ProvenanceLedgerParser.invalid(
            row, "reference tree is absent or not a real directory: " + tree);
      }
      Path notice = repositoryPath(
          tree, row.notice().substring("external-file:".length()), row);
      requireRegularFile(notice, "external reference notice evidence", row);
      ProvenancePolicy.TreeIdentity identity = ProvenanceTreeDigester.digest(tree);
      if (!row.sha256().equals(identity.sha256())) {
        throw ProvenanceLedgerParser.invalid(
            row,
            "reference snapshot digest is stale: expected " + row.sha256() + ", got "
                + identity.sha256() + " across " + identity.fileCount() + " files");
      }
      identities.put(row.artifactId(), identity);
    }
    return Map.copyOf(identities);
  }

  private static Path repositoryPath(
      Path root,
      String relative,
      ProvenancePolicy.Row row) {
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root) || resolved.equals(root)) {
      throw ProvenanceLedgerParser.invalid(row, "evidence path escapes its root");
    }
    return resolved;
  }

  private static void requireRegularFile(
      Path file,
      String description,
      ProvenancePolicy.Row row) {
    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
      throw ProvenanceLedgerParser.invalid(
          row, description + " is absent or not a real file: " + file);
    }
  }
}
