package io.riverdb.bench.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Removes only a staging tree proven to belong to the current publication attempt. */
final class AtomicArtifactCleanup {
  private static final String CLAIM_MARKER = ".river-bench-claim-v1";

  private AtomicArtifactCleanup() {
  }

  static void clean(
      Path claimDirectory, Path staging, List<Path> stagedFiles,
      byte[] expectedClaimMarker, boolean markerWritten) throws IOException {
    if (!claimDirectory.equals(staging.getParent())) {
      throw new IOException("refusing to clean staging outside owned run claim");
    }
    if (!Files.isDirectory(claimDirectory, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(claimDirectory)) {
      throw new IOException("refusing to clean non-directory owned run claim");
    }
    List<Path> claimEntries;
    try (var entries = Files.list(claimDirectory)) {
      claimEntries = entries.toList();
    }
    if (!markerWritten) {
      if (!claimEntries.isEmpty()) {
        throw new IOException("refusing to clean unmarked non-empty run claim");
      }
      Files.delete(claimDirectory);
      return;
    }
    Path marker = claimDirectory.resolve(CLAIM_MARKER);
    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(marker)
        || !java.util.Arrays.equals(expectedClaimMarker, Files.readAllBytes(marker))) {
      throw new IOException("refusing to clean run claim without matching ownership marker");
    }
    boolean stagingExists = Files.exists(staging, LinkOption.NOFOLLOW_LINKS);
    if (!claimEntries.contains(marker) || claimEntries.contains(staging) != stagingExists) {
      throw new IOException("refusing to clean run claim with unstable ownership content");
    }
    List<Path> actualStagedFiles = List.of();
    if (stagingExists) {
      if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(staging)) {
        throw new IOException("refusing to clean non-directory owned staging path");
      }
      Set<Path> expectedStagedFiles = new HashSet<>();
      for (Path file : stagedFiles) {
        if (!staging.equals(file.getParent())) {
          throw new IOException("refusing to clean a path outside owned staging directory");
        }
        expectedStagedFiles.add(file);
      }
      try (var entries = Files.list(staging)) {
        actualStagedFiles = entries.toList();
      }
      for (Path file : actualStagedFiles) {
        if (!staging.equals(file.getParent()) || !expectedStagedFiles.contains(file)
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(file)) {
          throw new IOException("refusing to clean staging with unexpected content");
        }
      }
    }
    for (Path entry : claimEntries) {
      if (!marker.equals(entry) && !staging.equals(entry)) {
        throw new IOException("refusing to clean run claim with unexpected content");
      }
    }
    for (int index = actualStagedFiles.size() - 1; index >= 0; index--) {
      Files.delete(actualStagedFiles.get(index));
    }
    if (stagingExists) Files.delete(staging);
    Files.delete(marker);
    Files.delete(claimDirectory);
  }
}
