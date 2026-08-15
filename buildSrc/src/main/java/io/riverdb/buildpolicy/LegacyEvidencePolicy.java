package io.riverdb.buildpolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Fail-closed verification for the selected legacy semantic-evidence matrix. */
public final class LegacyEvidencePolicy {
  public static final String HEADER = String.join(",",
      "source_id",
      "source_version",
      "source_path",
      "source_sha256",
      "provenance_entry",
      "feature",
      "disposition",
      "river_phase",
      "semantic_oracle",
      "expected_sqlstate",
      "owner",
      "notes"
  );

  private static final Pattern RELATIVE_PATH = Pattern.compile(
      "[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*"
  );
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SQLSTATE = Pattern.compile("[0-9A-Z]{5}");
  private static final Set<String> DISPOSITIONS = Set.of(
      "required", "adapt", "later", "unsupported"
  );

  private LegacyEvidencePolicy() {
  }

  /** Verifies every selected file against its approved external reference tree. */
  public static int verify(
      Path matrix,
      Path workspaceRoot,
      Map<String, ProvenancePolicy.Row> provenance
  ) throws IOException {
    List<String> lines = Files.readAllLines(matrix, StandardCharsets.UTF_8);
    if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
      throw new IllegalArgumentException("legacy support matrix header is invalid");
    }
    Path workspace = workspaceRoot.toAbsolutePath().normalize();
    int rows = 0;
    for (int index = 1; index < lines.size(); index++) {
      String line = lines.get(index);
      if (line.isBlank()) {
        throw invalid(index, "blank matrix row");
      }
      String[] fields = line.split(",", -1);
      if (fields.length != 12) {
        throw invalid(index, "expected 12 fields but found " + fields.length);
      }
      validateFields(fields, index);
      ProvenancePolicy.Row reference = provenance.get(fields[4]);
      if (reference == null || !"reference".equals(reference.artifactType())) {
        throw invalid(index, "provenance entry is not an approved reference");
      }
      if (!fields[0].equals(fields[4]) || !fields[1].equals(reference.revision())) {
        throw invalid(index, "source identity or revision differs from provenance");
      }
      Path file = resolve(workspace, reference.location(), fields[2], index);
      if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
        throw invalid(index, "selected source is absent or not a regular file");
      }
      String actual = sha256(file);
      if (!fields[3].equals(actual)) {
        throw invalid(index, "selected source digest mismatch: expected "
            + fields[3] + ", got " + actual);
      }
      rows++;
    }
    if (rows == 0) {
      throw new IllegalArgumentException("legacy support matrix has no selected rows");
    }
    return rows;
  }

  private static void validateFields(String[] fields, int index) {
    for (String field : fields) {
      if (field.isBlank() || !field.equals(field.strip())) {
        throw invalid(index, "matrix fields must be nonblank without outer whitespace");
      }
    }
    if (!RELATIVE_PATH.matcher(fields[2]).matches()) {
      throw invalid(index, "source path is not canonical and relative");
    }
    String[] segments = fields[2].split("/");
    for (String segment : segments) {
      if (".".equals(segment) || "..".equals(segment)) {
        throw invalid(index, "source path is not canonical and relative");
      }
    }
    if (".DS_Store".equals(segments[segments.length - 1])) {
      throw invalid(index, "selected source is excluded from the reference identity");
    }
    if (!SHA256.matcher(fields[3]).matches()) {
      throw invalid(index, "source SHA-256 is malformed");
    }
    if (!DISPOSITIONS.contains(fields[6])) {
      throw invalid(index, "disposition is unsupported");
    }
    if (!SQLSTATE.matcher(fields[9]).matches()) {
      throw invalid(index, "expected SQLSTATE is malformed");
    }
  }

  private static Path resolve(
      Path workspace, String location, String relative, int index) {
    String prefix = "external-workspace:";
    if (!location.startsWith(prefix)) {
      throw invalid(index, "reference location is not an external workspace");
    }
    Path tree = workspace.resolve(location.substring(prefix.length())).normalize();
    Path file = tree.resolve(relative).normalize();
    if (!tree.getParent().equals(workspace) || !file.startsWith(tree)) {
      throw invalid(index, "selected source path escapes its reference tree");
    }
    return file;
  }

  private static String sha256(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("required SHA-256 provider is unavailable", failure);
    }
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[16 * 1024];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static IllegalArgumentException invalid(int index, String detail) {
    return new IllegalArgumentException(
        "legacy support matrix line " + (index + 1) + ": " + detail
    );
  }
}
