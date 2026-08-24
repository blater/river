package io.riverdb.bench.harness;

import io.riverdb.bench.harness.BenchmarkArtifactDocuments.PreparedWorkload;
import io.riverdb.bench.harness.BenchmarkArtifactDocuments.PublicationPlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Exclusively claims, verifies, publishes, and cleans one create-once artifact tree. */
final class AtomicArtifactPublisher {
  private static final String CLAIM_MARKER = ".river-bench-claim-v1";
  private static final String STAGING_DIRECTORY = ".pending";
  private static final String STAGING_MARKER = ".river-bench-staging-v1";
  private static final String PUBLISHED_DIRECTORY = "artifacts";
  private static final Pattern RUN_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{7,127}$");

  ArtifactWriteResult publish(Path outputRoot, String runId, PublicationPlan plan)
      throws IOException {
    return claimAndPublish(outputRoot, runId, () -> plan);
  }

  ArtifactWriteResult publishStreaming(Path outputRoot, String runId, PlanPreparation preparation)
      throws IOException {
    return claimAndPublish(outputRoot, runId, preparation);
  }

  ArtifactWriteResult advisoryTarget(Path outputRoot, String runId) {
    ClaimPath path = claimPath(outputRoot, runId);
    if (path == null) return invalid("$: invalid output root or run id");
    return Files.exists(path.claimDirectory(), LinkOption.NOFOLLOW_LINKS)
        ? targetExists(path.claimDirectory()) : null;
  }

  private ArtifactWriteResult claimAndPublish(
      Path outputRoot, String runId, PlanPreparation preparation) throws IOException {
    ClaimPath path = claimPath(outputRoot, runId);
    if (path == null) return invalid("$: invalid output root or run id");
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
        AtomicArtifactCleanup.clean(path.claimDirectory(), staging, stagedFiles, claimMarker, true);
        return new ArtifactWriteResult(plan.status(), null, plan.validation());
      }
      Files.createDirectory(staging);
      stage(staging, stagedFiles, runId, plan);
      Files.move(staging, path.publishedDirectory(), StandardCopyOption.ATOMIC_MOVE);
      return new ArtifactWriteResult(ArtifactWriteStatus.WRITTEN, path.publishedDirectory(), null);
    } catch (AtomicMoveNotSupportedException exception) {
      AtomicArtifactCleanup.clean(path.claimDirectory(), staging, stagedFiles, claimMarker,
          markerWritten);
      return new ArtifactWriteResult(ArtifactWriteStatus.ATOMIC_PUBLISH_UNAVAILABLE,
          path.publishedDirectory(), null);
    } catch (IOException | RuntimeException exception) {
      if (Files.notExists(path.publishedDirectory(), LinkOption.NOFOLLOW_LINKS)) {
        try {
          AtomicArtifactCleanup.clean(path.claimDirectory(), staging, stagedFiles, claimMarker,
              markerWritten);
        } catch (IOException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  private void stage(Path staging, List<Path> stagedFiles, String runId, PublicationPlan plan)
      throws IOException {
    writeTracked(staging, stagedFiles, STAGING_MARKER,
        ("river-bench-staging-v1\nrun_id=" + runId + "\n").getBytes(StandardCharsets.UTF_8));
    writeTracked(staging, stagedFiles, "manifest.json", plan.manifestBytes());
    writeTracked(staging, stagedFiles, "samples.tsv", plan.sampleBytes());
    for (PreparedWorkload workload : plan.workloads()) {
      Path path = staging.resolve(workload.fileName());
      stagedFiles.add(path);
      AtomicArtifactVerifier.writeWorkload(path, workload);
    }
    AtomicArtifactVerifier.verifyFile(staging.resolve("manifest.json"),
        WorkloadChecksums.sha256(plan.manifestBytes()));
    AtomicArtifactVerifier.verifyFile(staging.resolve("samples.tsv"),
        WorkloadChecksums.sha256(plan.sampleBytes()));
    for (PreparedWorkload workload : plan.workloads()) {
      AtomicArtifactVerifier.verifyWorkload(staging.resolve(workload.fileName()), workload);
    }
    writeTracked(staging, stagedFiles, "result.json", plan.resultBytes());
    AtomicArtifactVerifier.verifyFile(staging.resolve("result.json"),
        WorkloadChecksums.sha256(plan.resultBytes()));
    AtomicArtifactVerifier.verifyPlan(staging, plan);
  }

  private static void writeTracked(Path staging, List<Path> stagedFiles, String name, byte[] bytes)
      throws IOException {
    Path path = staging.resolve(name);
    stagedFiles.add(path);
    writeNew(path, bytes);
  }

  private static ClaimPath claimPath(Path outputRoot, String runId) {
    if (outputRoot == null || runId == null || !RUN_ID.matcher(runId).matches()) return null;
    Path normalizedRoot = outputRoot.toAbsolutePath().normalize();
    Path claimDirectory = normalizedRoot.resolve(runId).normalize();
    if (!normalizedRoot.equals(claimDirectory.getParent())) return null;
    return new ClaimPath(normalizedRoot, claimDirectory,
        claimDirectory.resolve(PUBLISHED_DIRECTORY));
  }

  private static ArtifactWriteResult invalid(String error) {
    return new ArtifactWriteResult(ArtifactWriteStatus.INVALID_DOCUMENT, null,
        new SchemaValidation(false, List.of(error)));
  }

  private static ArtifactWriteResult targetExists(Path claimDirectory) {
    return new ArtifactWriteResult(ArtifactWriteStatus.TARGET_EXISTS,
        claimDirectory.resolve(PUBLISHED_DIRECTORY), null);
  }

  private static byte[] claimMarkerBytes(String runId) {
    return ("river-bench-claim-v1\nrun_id=" + runId + "\n").getBytes(StandardCharsets.UTF_8);
  }

  private static void writeNew(Path path, byte[] bytes) throws IOException {
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }

  @FunctionalInterface
  interface PlanPreparation {
    PublicationPlan prepare() throws IOException;
  }

  private record ClaimPath(Path outputRoot, Path claimDirectory, Path publishedDirectory) { }
}
