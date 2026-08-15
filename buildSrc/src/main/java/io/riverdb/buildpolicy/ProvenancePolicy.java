package io.riverdb.buildpolicy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Fail-closed validation for River's provenance ledger and reference snapshots. */
public final class ProvenancePolicy {
  public static final String HEADER = String.join(",",
      "artifact_id",
      "artifact_type",
      "name",
      "upstream",
      "revision",
      "location",
      "sha256",
      "digest_algorithm",
      "license",
      "notice",
      "use",
      "vendoring",
      "approval"
  );
  public static final String FILE_DIGEST = "sha256-file-v1";
  public static final String TREE_DIGEST = "river-tree-sha256-v2";
  public static final String REPOSITORY_DIGEST = "repository-head";

  private static final byte[] TREE_HEADER =
      "river-tree-sha256-v2\n".getBytes(StandardCharsets.US_ASCII);
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");
  private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern APPROVAL = Pattern.compile(
      "approved:(\\d{4}-\\d{2}-\\d{2}):project-owner-decision"
  );
  private static final Pattern EXTERNAL_LOCATION = Pattern.compile(
      "external-workspace:([A-Za-z0-9][A-Za-z0-9._-]*)"
  );
  private static final Pattern REPOSITORY_LOCATION = Pattern.compile(
      "repository:(\\.|[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*)"
  );
  private static final Pattern NOTICE = Pattern.compile(
      "(repository-file|artifact-file|external-file):"
          + "([A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*)|not-vendored"
  );
  private static final Set<String> TYPES = Set.of(
      "source", "tool", "dependency", "reference", "dataset"
  );
  private static final String VERIFICATION_NAMESPACE =
      "https://schema.gradle.org/dependency-verification";

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
      int lineNumber
  ) {
  }

  /** A deterministic external-tree identity and its regular-file count. */
  public record TreeIdentity(String sha256, int fileCount) {
  }

  /** Parses and validates every row in the v2 ledger. */
  public static Map<String, Row> read(Path ledger) throws IOException {
    return parse(Files.readAllLines(ledger, StandardCharsets.UTF_8));
  }

  /** Parses ledger lines; exposed so executable negative fixtures share the live parser. */
  public static Map<String, Row> parse(List<String> lines) {
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("provenance ledger is empty");
    }
    if (!HEADER.equals(lines.getFirst())) {
      throw new IllegalArgumentException("provenance ledger header does not match v2 schema");
    }

    Map<String, Row> rows = new LinkedHashMap<>();
    for (int index = 1; index < lines.size(); index++) {
      int lineNumber = index + 1;
      String line = lines.get(index);
      if (line.isBlank()) {
        throw new IllegalArgumentException(
            "blank provenance ledger row at line " + lineNumber
        );
      }
      String[] fields = line.split(",", -1);
      if (fields.length != 13) {
        throw new IllegalArgumentException("provenance ledger line " + lineNumber
            + " has " + fields.length + " fields, expected 13");
      }
      for (String field : fields) {
        if (!field.equals(field.strip())) {
          throw new IllegalArgumentException(
              "provenance ledger fields must not have outer whitespace at line "
                  + lineNumber
          );
        }
      }

      Row row = new Row(
          fields[0], fields[1], fields[2], fields[3], fields[4], fields[5],
          fields[6], fields[7], fields[8], fields[9], fields[10], fields[11],
          fields[12], lineNumber
      );
      validate(row);
      if (rows.putIfAbsent(row.artifactId(), row) != null) {
        throw new IllegalArgumentException(
            "duplicate provenance artifact ID " + row.artifactId()
        );
      }
    }
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("provenance ledger has no artifact rows");
    }
    return Map.copyOf(rows);
  }

  /** Checks repository-local notice evidence and embedded artifact evidence. */
  public static void verifyRepositoryNotices(Path repository, Iterable<Row> rows)
      throws IOException {
    Path normalizedRepository = repository.toAbsolutePath().normalize();
    for (Row row : rows) {
      if (row.notice().startsWith("repository-file:")) {
        Path notice = repositoryPath(
            normalizedRepository,
            row.notice().substring("repository-file:".length()),
            row
        );
        requireRegularFile(notice, "repository notice", row);
      } else if (row.notice().startsWith("artifact-file:")) {
        Matcher location = REPOSITORY_LOCATION.matcher(row.location());
        if (!location.matches() || ".".equals(location.group(1))) {
          throw invalid(row, "artifact-file notice requires a repository artifact");
        }
        Path artifact = repositoryPath(normalizedRepository, location.group(1), row);
        requireRegularFile(artifact, "repository artifact", row);
        String entry = row.notice().substring("artifact-file:".length());
        try (ZipFile zip = new ZipFile(artifact.toFile())) {
          if (zip.getEntry(entry) == null) {
            throw invalid(row, "artifact notice entry is missing: " + entry);
          }
        }
      }
    }
  }

  /**
   * Verifies external reference trees. This is intentionally separate from the
   * ordinary build because those approved trees are not part of a clean River
   * checkout.
   */
  public static Map<String, TreeIdentity> verifyExternalReferences(
      Path workspaceRoot,
      Iterable<Row> rows
  ) throws IOException {
    Path normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize();
    Map<String, TreeIdentity> identities = new LinkedHashMap<>();
    for (Row row : rows) {
      if (!"reference".equals(row.artifactType())) {
        continue;
      }
      Matcher location = EXTERNAL_LOCATION.matcher(row.location());
      if (!location.matches()) {
        throw invalid(row, "reference location is malformed");
      }
      Path tree = normalizedWorkspace.resolve(location.group(1)).normalize();
      if (!tree.getParent().equals(normalizedWorkspace)) {
        throw invalid(row, "reference location escapes the workspace root");
      }
      if (!Files.isDirectory(tree) || Files.isSymbolicLink(tree)) {
        throw invalid(row, "reference tree is absent or not a real directory: " + tree);
      }
      String noticeRelative = row.notice().substring("external-file:".length());
      Path notice = repositoryPath(tree, noticeRelative, row);
      requireRegularFile(notice, "external reference notice evidence", row);

      TreeIdentity identity = treeIdentity(tree);
      if (!row.sha256().equals(identity.sha256())) {
        throw invalid(row, "reference snapshot digest is stale: expected "
            + row.sha256() + ", got " + identity.sha256()
            + " across " + identity.fileCount() + " files");
      }
      identities.put(row.artifactId(), identity);
    }
    return Map.copyOf(identities);
  }

  /** Checks that dependency rows and resolved unclassified JARs match exactly. */
  public static void verifyResolvedDependencies(
      Iterable<Row> rows,
      Map<String, String> resolved
  ) {
    Map<String, Row> declared = new LinkedHashMap<>();
    for (Row row : rows) {
      if (!"dependency".equals(row.artifactType())) {
        continue;
      }
      if (row.name().chars().filter(value -> value == ':').count() != 1) {
        throw invalid(row, "dependency coordinate must be group:name");
      }
      String coordinate = row.name() + ":" + row.revision();
      if (declared.putIfAbsent(coordinate, row) != null) {
        throw invalid(row, "duplicate dependency coordinate " + coordinate);
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
          "resolved dependencies missing from provenance ledger: " + missing
      );
    }
    if (!stale.isEmpty()) {
      throw new IllegalArgumentException(
          "provenance dependency rows are not resolved by the build: " + stale
      );
    }
    resolved.forEach((coordinate, actual) -> {
      Row row = declared.get(coordinate);
      if (!row.sha256().equals(actual)) {
        throw invalid(row, "dependency checksum mismatch for " + coordinate
            + ": expected " + row.sha256() + ", got " + actual);
      }
    });
  }

  /**
   * Checks that Gradle verification is fail-closed and that its JAR identities
   * exactly match the independently resolved dependency set.
   */
  public static void verifyGradleMetadata(
      Path metadata,
      Map<String, String> resolved
  ) throws IOException {
    if (!Files.isRegularFile(metadata) || Files.isSymbolicLink(metadata)) {
      throw new IllegalArgumentException(
          "Gradle dependency verification metadata is absent or not a real file"
      );
    }
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    try {
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var document = factory.newDocumentBuilder().parse(metadata.toFile());
      Element root = document.getDocumentElement();
      if (!"verification-metadata".equals(root.getLocalName())
          || !VERIFICATION_NAMESPACE.equals(root.getNamespaceURI())) {
        throw new IllegalArgumentException(
            "Gradle dependency verification metadata has an unexpected root"
        );
      }
      requireSingleText(root, "verify-metadata", "true");
      requireAbsent(root, "trusted-artifacts");
      requireAbsent(root, "trusted-keys");
      requireAbsent(root, "ignored-keys");

      Map<String, String> verifiedJars = new LinkedHashMap<>();
      var components = root.getElementsByTagNameNS(VERIFICATION_NAMESPACE, "component");
      for (int componentIndex = 0;
          componentIndex < components.getLength();
          componentIndex++) {
        Element component = (Element) components.item(componentIndex);
        String group = requiredAttribute(component, "group");
        String name = requiredAttribute(component, "name");
        String version = requiredAttribute(component, "version");
        String coordinate = group + ":" + name + ":" + version;
        var artifacts = component.getElementsByTagNameNS(
            VERIFICATION_NAMESPACE,
            "artifact"
        );
        for (int artifactIndex = 0;
            artifactIndex < artifacts.getLength();
            artifactIndex++) {
          Element artifact = (Element) artifacts.item(artifactIndex);
          String artifactName = requiredAttribute(artifact, "name");
          var checksums = artifact.getElementsByTagNameNS(
              VERIFICATION_NAMESPACE,
              "sha256"
          );
          if (checksums.getLength() != 1) {
            throw new IllegalArgumentException(
                "Gradle verification artifact must have one SHA-256: " + artifactName
            );
          }
          String checksum = requiredAttribute((Element) checksums.item(0), "value");
          if (!SHA256.matcher(checksum).matches()) {
            throw new IllegalArgumentException(
                "Gradle verification SHA-256 is malformed: " + artifactName
            );
          }
          if (artifactName.endsWith(".jar")) {
            String previous = verifiedJars.putIfAbsent(coordinate, checksum);
            if (previous != null) {
              throw new IllegalArgumentException(
                  "Gradle verification has multiple JARs for " + coordinate
              );
            }
          }
        }
      }
      if (!verifiedJars.equals(resolved)) {
        throw new IllegalArgumentException(
            "Gradle verification JAR set differs from resolved dependencies: expected "
                + resolved + ", got " + verifiedJars
        );
      }
    } catch (ParserConfigurationException | SAXException failure) {
      throw new IllegalArgumentException(
          "Gradle dependency verification metadata is malformed",
          failure
      );
    }
  }

  /** Computes the platform-independent River tree digest documented by P01. */
  public static TreeIdentity treeIdentity(Path root) throws IOException {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    if (!Files.isDirectory(normalizedRoot) || Files.isSymbolicLink(normalizedRoot)) {
      throw new IllegalArgumentException("snapshot root must be a real directory");
    }
    List<Path> files = new ArrayList<>();
    try (var paths = Files.walk(normalizedRoot)) {
      paths.forEach(path -> {
        if (Files.isSymbolicLink(path)) {
          throw new IllegalArgumentException("snapshot contains a symbolic link: " + path);
        }
        BasicFileAttributes attributes;
        try {
          attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException failure) {
          throw new SnapshotReadFailure(path, failure);
        }
        if (attributes.isRegularFile()) {
          if (!path.getFileName().toString().equals(".DS_Store")) {
            files.add(path);
          }
        } else if (!attributes.isDirectory()) {
          throw new IllegalArgumentException("snapshot contains a special file: " + path);
        }
      });
    } catch (SnapshotReadFailure failure) {
      throw failure.failure();
    }
    files.sort(Comparator.comparing(path -> relative(normalizedRoot, path)));
    if (files.isEmpty()) {
      throw new IllegalArgumentException("snapshot tree has no regular files");
    }

    MessageDigest tree = sha256Digest();
    tree.update(TREE_HEADER);
    for (Path file : files) {
      String relative = relative(normalizedRoot, file);
      byte[] pathBytes = relative.getBytes(StandardCharsets.UTF_8);
      byte[] fileDigest = sha256File(file);
      tree.update("file\0".getBytes(StandardCharsets.US_ASCII));
      tree.update(Integer.toString(pathBytes.length).getBytes(StandardCharsets.US_ASCII));
      tree.update((byte) ':');
      tree.update(pathBytes);
      tree.update((byte) 0);
      tree.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.US_ASCII));
      tree.update((byte) 0);
      tree.update(fileDigest);
      tree.update((byte) '\n');
    }
    return new TreeIdentity(HexFormat.of().formatHex(tree.digest()), files.size());
  }

  private static void validate(Row row) {
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

  private static void validateSource(Row row) {
    if (!"repository:.".equals(row.location())
        || !"none".equals(row.sha256())
        || !REPOSITORY_DIGEST.equals(row.digestAlgorithm())
        || !row.notice().startsWith("repository-file:")) {
      throw invalid(row, "source row must identify the live repository and its notice file");
    }
  }

  private static void validateUpstream(Row row) {
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

  private static void validateDependency(Row row) {
    if (!"gradle-cache".equals(row.location())
        || !SHA256.matcher(row.sha256()).matches()
        || !FILE_DIGEST.equals(row.digestAlgorithm())
        || !"not-vendored".equals(row.notice())) {
      throw invalid(row, "dependency row fields are inconsistent");
    }
  }

  private static void validateTool(Row row) {
    boolean wrapperCache = "wrapper-cache".equals(row.location());
    boolean repositoryArtifact = REPOSITORY_LOCATION.matcher(row.location()).matches()
        && !"repository:.".equals(row.location());
    if ((!wrapperCache && !repositoryArtifact)
        || !SHA256.matcher(row.sha256()).matches()
        || !FILE_DIGEST.equals(row.digestAlgorithm())) {
      throw invalid(row, "tool row fields are inconsistent");
    }
    if (repositoryArtifact && !row.notice().startsWith("artifact-file:")) {
      throw invalid(row, "repository tool must identify embedded notice evidence");
    }
    if (wrapperCache && !"not-vendored".equals(row.notice())) {
      throw invalid(row, "cached tool must use the not-vendored notice outcome");
    }
  }

  private static void validateExternalTree(Row row) {
    if (!EXTERNAL_LOCATION.matcher(row.location()).matches()
        || !SHA256.matcher(row.sha256()).matches()
        || !TREE_DIGEST.equals(row.digestAlgorithm())
        || !row.notice().startsWith("external-file:")) {
      throw invalid(row, "external reference fields are inconsistent");
    }
  }

  private static void rejectUnresolved(String value, String field, Row row) {
    String normalized = value.toLowerCase(java.util.Locale.ROOT);
    if (normalized.contains("pending") || normalized.contains("unknown")
        || normalized.contains("tbd")) {
      throw invalid(row, field + " is unresolved");
    }
  }

  private static void requirePresent(String value, String field, Row row) {
    if (value.isBlank()) {
      throw invalid(row, field + " is missing");
    }
  }

  private static void requireSingleText(Element root, String name, String expected) {
    var nodes = root.getElementsByTagNameNS(VERIFICATION_NAMESPACE, name);
    if (nodes.getLength() != 1 || !expected.equals(nodes.item(0).getTextContent().strip())) {
      throw new IllegalArgumentException(
          "Gradle verification metadata requires " + name + "=" + expected
      );
    }
  }

  private static void requireAbsent(Element root, String name) {
    if (root.getElementsByTagNameNS(VERIFICATION_NAMESPACE, name).getLength() != 0) {
      throw new IllegalArgumentException(
          "Gradle verification metadata must not contain " + name
      );
    }
  }

  private static String requiredAttribute(Element element, String name) {
    String value = element.getAttribute(name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(
          "Gradle verification metadata has a blank " + name + " attribute"
      );
    }
    return value;
  }

  private static Path repositoryPath(Path root, String relative, Row row) {
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root) || resolved.equals(root)) {
      throw invalid(row, "evidence path escapes its root");
    }
    return resolved;
  }

  private static void requireRegularFile(Path file, String description, Row row) {
    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
      throw invalid(row, description + " is absent or not a real file: " + file);
    }
  }

  private static String relative(Path root, Path file) {
    return root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
  }

  private static byte[] sha256File(Path file) throws IOException {
    MessageDigest digest = sha256Digest();
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[16 * 1024];
      while (true) {
        int read = input.read(buffer);
        if (read < 0) {
          break;
        }
        digest.update(buffer, 0, read);
      }
    }
    return digest.digest();
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("required SHA-256 provider is unavailable", failure);
    }
  }

  private static IllegalArgumentException invalid(Row row, String detail) {
    return new IllegalArgumentException("provenance row " + row.artifactId()
        + " at line " + row.lineNumber() + ": " + detail);
  }

  private static final class SnapshotReadFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final IOException failure;

    SnapshotReadFailure(Path path, IOException failure) {
      super("could not read snapshot path " + path, failure);
      this.failure = failure;
    }

    IOException failure() {
      return failure;
    }
  }
}
