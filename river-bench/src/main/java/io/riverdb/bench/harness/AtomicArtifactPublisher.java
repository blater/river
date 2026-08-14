package io.riverdb.bench.harness;

import io.riverdb.bench.harness.BenchmarkArtifactDocuments.ContentWrite;
import io.riverdb.bench.harness.BenchmarkArtifactDocuments.PreparedWorkload;
import io.riverdb.bench.harness.BenchmarkArtifactDocuments.PublicationPlan;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Exclusively claims, verifies, publishes, and cleans one create-once artifact tree. */
final class AtomicArtifactPublisher {
  private static final String CLAIM_MARKER = ".river-bench-claim-v1";
  private static final String STAGING_DIRECTORY = ".pending";
  private static final String STAGING_MARKER = ".river-bench-staging-v1";
  private static final String PUBLISHED_DIRECTORY = "artifacts";
  private static final int OBSERVATION_SCRATCH_BYTES = 64 * 1024;
  private static final Pattern RUN_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{7,127}$");

  ArtifactWriteResult publish(Path outputRoot, String runId, PublicationPlan plan)
      throws IOException {
    return claimAndPublish(outputRoot, runId, () -> plan);
  }

  ArtifactWriteResult publishStreaming(
      Path outputRoot,
      String runId,
      PlanPreparation preparation) throws IOException {
    return claimAndPublish(outputRoot, runId, preparation);
  }

  ArtifactWriteResult advisoryTarget(Path outputRoot, String runId) {
    ClaimPath path = claimPath(outputRoot, runId);
    if (path == null) {
      return invalid("$: invalid output root or run id");
    }
    if (Files.exists(path.claimDirectory(), LinkOption.NOFOLLOW_LINKS)) {
      return targetExists(path.claimDirectory());
    }
    return null;
  }

  private ArtifactWriteResult claimAndPublish(
      Path outputRoot,
      String runId,
      PlanPreparation preparation) throws IOException {
    ClaimPath path = claimPath(outputRoot, runId);
    if (path == null) {
      return invalid("$: invalid output root or run id");
    }
    Files.createDirectories(path.outputRoot());
    if (Files.exists(path.claimDirectory(), LinkOption.NOFOLLOW_LINKS)) {
      return targetExists(path.claimDirectory());
    }
    try {
      Files.createDirectory(path.claimDirectory());
    } catch (FileAlreadyExistsException exception) {
      return targetExists(path.claimDirectory());
    }

    byte[] claimMarker = claimMarkerBytes(runId);
    boolean markerWritten = false;
    Path staging = path.claimDirectory().resolve(STAGING_DIRECTORY);
    List<Path> stagedFiles = new ArrayList<>();
    try {
      writeNew(path.claimDirectory().resolve(CLAIM_MARKER), claimMarker);
      markerWritten = true;

      PublicationPlan plan = preparation.prepare();
      if (plan.status() != ArtifactWriteStatus.WRITTEN) {
        cleanupOwnedAttempt(path.claimDirectory(), staging, stagedFiles, claimMarker, true);
        return new ArtifactWriteResult(plan.status(), null, plan.validation());
      }

      Files.createDirectory(staging);
      stage(staging, stagedFiles, runId, plan);
      move(staging, path.publishedDirectory());
      return new ArtifactWriteResult(
          ArtifactWriteStatus.WRITTEN, path.publishedDirectory(), null);
    } catch (AtomicMoveNotSupportedException exception) {
      cleanupOwnedAttempt(
          path.claimDirectory(), staging, stagedFiles, claimMarker, markerWritten);
      return new ArtifactWriteResult(
          ArtifactWriteStatus.ATOMIC_PUBLISH_UNAVAILABLE,
          path.publishedDirectory(),
          null);
    } catch (IOException | RuntimeException exception) {
      if (Files.notExists(path.publishedDirectory(), LinkOption.NOFOLLOW_LINKS)) {
        try {
          cleanupOwnedAttempt(
              path.claimDirectory(), staging, stagedFiles, claimMarker, markerWritten);
        } catch (IOException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  private void stage(
      Path staging,
      List<Path> stagedFiles,
      String runId,
      PublicationPlan plan) throws IOException {
    Path ownerPath = staging.resolve(STAGING_MARKER);
    stagedFiles.add(ownerPath);
    writeNew(ownerPath, stagingMarkerBytes(runId));
    Path manifestPath = staging.resolve("manifest.json");
    stagedFiles.add(manifestPath);
    writeNew(manifestPath, plan.manifestBytes());
    Path samplesPath = staging.resolve("samples.tsv");
    stagedFiles.add(samplesPath);
    writeNew(samplesPath, plan.sampleBytes());
    for (PreparedWorkload workload : plan.workloads()) {
      Path path = staging.resolve(workload.fileName());
      stagedFiles.add(path);
      writeWorkloadNew(path, workload);
    }
    verifyFile(manifestPath, WorkloadChecksums.sha256(plan.manifestBytes()));
    verifyFile(samplesPath, WorkloadChecksums.sha256(plan.sampleBytes()));
    for (PreparedWorkload workload : plan.workloads()) {
      verifyWorkloadFile(staging.resolve(workload.fileName()), workload);
    }

    Path resultPath = staging.resolve("result.json");
    stagedFiles.add(resultPath);
    writeNew(resultPath, plan.resultBytes());
    verifyFile(resultPath, WorkloadChecksums.sha256(plan.resultBytes()));
    verifyPlanReferences(staging, plan);
  }

  private void move(Path staging, Path publishedDirectory) throws IOException {
    Files.move(staging, publishedDirectory, StandardCopyOption.ATOMIC_MOVE);
  }

  private void cleanupOwnedAttempt(
      Path claimDirectory,
      Path staging,
      List<Path> stagedFiles,
      byte[] expectedClaimMarker,
      boolean markerWritten) throws IOException {
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
    if (!claimEntries.contains(marker)
        || claimEntries.contains(staging) != stagingExists) {
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
        if (!staging.equals(file.getParent())
            || !expectedStagedFiles.contains(file)
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
    if (stagingExists) {
      Files.delete(staging);
    }
    Files.delete(marker);
    Files.delete(claimDirectory);
  }

  private static ClaimPath claimPath(Path outputRoot, String runId) {
    if (outputRoot == null || runId == null || !RUN_ID.matcher(runId).matches()) {
      return null;
    }
    Path normalizedRoot = outputRoot.toAbsolutePath().normalize();
    Path claimDirectory = normalizedRoot.resolve(runId).normalize();
    if (!normalizedRoot.equals(claimDirectory.getParent())) {
      return null;
    }
    return new ClaimPath(
        normalizedRoot,
        claimDirectory,
        claimDirectory.resolve(PUBLISHED_DIRECTORY));
  }

  private static ArtifactWriteResult invalid(String error) {
    return new ArtifactWriteResult(
        ArtifactWriteStatus.INVALID_DOCUMENT,
        null,
        new SchemaValidation(false, List.of(error)));
  }

  private static ArtifactWriteResult targetExists(Path claimDirectory) {
    return new ArtifactWriteResult(
        ArtifactWriteStatus.TARGET_EXISTS,
        claimDirectory.resolve(PUBLISHED_DIRECTORY),
        null);
  }

  private static void verifyFile(Path path, String expectedSha256) throws IOException {
    ObservedFile observed = observeFile(path);
    if (!expectedSha256.equals(observed.sha256())) {
      throw new IOException("staged artifact digest mismatch: " + path.getFileName());
    }
  }

  private static void verifyWorkloadFile(Path path, PreparedWorkload workload)
      throws IOException {
    ObservedFile observed = observeFile(path);
    if (observed.byteCount() != workload.byteCount()
        || observed.dataRows() != workload.recordCount()
        || !observed.sha256().equals(workload.sha256())) {
      throw new IOException("staged workload observation mismatch: " + path.getFileName());
    }
  }

  private static ObservedFile observeFile(Path path) throws IOException {
    MessageDigest digest = WorkloadChecksums.sha256Digest();
    long bytes = 0;
    long lineFeeds = 0;
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[OBSERVATION_SCRATCH_BYTES];
      int length;
      while ((length = input.read(buffer)) >= 0) {
        if (length > 0) {
          digest.update(buffer, 0, length);
          bytes = Math.addExact(bytes, length);
          for (int index = 0; index < length; index++) {
            if (buffer[index] == '\n') {
              lineFeeds = Math.addExact(lineFeeds, 1);
            }
          }
        }
      }
    }
    return new ObservedFile(
        bytes,
        lineFeeds < 1 ? -1 : lineFeeds - 1,
        WorkloadChecksums.hex(digest.digest()));
  }

  private static void verifyPlanReferences(Path staging, PublicationPlan plan)
      throws IOException {
    verifyFile(staging.resolve("manifest.json"), WorkloadChecksums.sha256(plan.manifestBytes()));
    verifyFile(staging.resolve("samples.tsv"), WorkloadChecksums.sha256(plan.sampleBytes()));
    for (PreparedWorkload workload : plan.workloads()) {
      Path path = staging.resolve(workload.fileName()).normalize();
      if (!staging.equals(path.getParent())) {
        throw new IOException("publication plan reference escapes staging directory");
      }
      verifyFile(path, workload.sha256());
    }
  }

  private static byte[] stagingMarkerBytes(String runId) {
    return ("river-bench-staging-v1\nrun_id=" + runId + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] claimMarkerBytes(String runId) {
    return ("river-bench-claim-v1\nrun_id=" + runId + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static void writeNew(Path path, byte[] bytes) throws IOException {
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }

  private static void writeWorkloadNew(Path path, PreparedWorkload workload)
      throws IOException {
    ContentWrite content;
    try (OutputStream output = Files.newOutputStream(
        path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      content = workload.content().write(output);
    }
    if (content.status() != StreamingGenerationStatus.GENERATED
        || content.rowCount() != workload.recordCount()
        || content.byteCount() != workload.byteCount()
        || Files.size(path) != workload.byteCount()
        || !content.sha256().equals(workload.sha256())) {
      throw new IOException(
          "streamed workload changed between preflight and staging: " + workload.name());
    }
  }

  @FunctionalInterface
  interface PlanPreparation {
    PublicationPlan prepare() throws IOException;
  }

  private record ClaimPath(Path outputRoot, Path claimDirectory, Path publishedDirectory) {
  }

  private record ObservedFile(long byteCount, long dataRows, String sha256) {
  }
}
