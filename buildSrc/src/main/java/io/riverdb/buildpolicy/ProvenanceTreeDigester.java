package io.riverdb.buildpolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Computes the platform-independent River tree digest documented by P01. */
final class ProvenanceTreeDigester {
  private static final byte[] TREE_HEADER =
      "river-tree-sha256-v2\n".getBytes(StandardCharsets.US_ASCII);

  private ProvenanceTreeDigester() {
  }

  static ProvenancePolicy.TreeIdentity digest(Path root) throws IOException {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    if (!Files.isDirectory(normalizedRoot) || Files.isSymbolicLink(normalizedRoot)) {
      throw new IllegalArgumentException("snapshot root must be a real directory");
    }
    List<Path> files = regularFiles(normalizedRoot);
    files.sort(Comparator.comparing(path -> relative(normalizedRoot, path)));
    if (files.isEmpty()) {
      throw new IllegalArgumentException("snapshot tree has no regular files");
    }
    MessageDigest tree = sha256Digest();
    tree.update(TREE_HEADER);
    for (Path file : files) {
      appendFileIdentity(tree, normalizedRoot, file);
    }
    return new ProvenancePolicy.TreeIdentity(HexFormat.of().formatHex(tree.digest()), files.size());
  }

  private static List<Path> regularFiles(Path root) throws IOException {
    List<Path> files = new ArrayList<>();
    try (var paths = Files.walk(root)) {
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
    return files;
  }

  private static void appendFileIdentity(MessageDigest tree, Path root, Path file)
      throws IOException {
    String relative = relative(root, file);
    byte[] pathBytes = relative.getBytes(StandardCharsets.UTF_8);
    tree.update("file\0".getBytes(StandardCharsets.US_ASCII));
    tree.update(Integer.toString(pathBytes.length).getBytes(StandardCharsets.US_ASCII));
    tree.update((byte) ':');
    tree.update(pathBytes);
    tree.update((byte) 0);
    tree.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.US_ASCII));
    tree.update((byte) 0);
    tree.update(sha256File(file));
    tree.update((byte) '\n');
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
          return digest.digest();
        }
        digest.update(buffer, 0, read);
      }
    }
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("required SHA-256 provider is unavailable", failure);
    }
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
