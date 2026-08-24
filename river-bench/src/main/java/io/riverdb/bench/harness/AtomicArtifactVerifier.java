package io.riverdb.bench.harness;

import io.riverdb.bench.harness.BenchmarkArtifactDocuments.ContentWrite;
import io.riverdb.bench.harness.BenchmarkArtifactDocuments.PreparedWorkload;
import io.riverdb.bench.harness.BenchmarkArtifactDocuments.PublicationPlan;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/** Writes and verifies staged benchmark artifact files. */
final class AtomicArtifactVerifier {
  private static final int SCRATCH_BYTES = 64 * 1024;

  private AtomicArtifactVerifier() {
  }

  static void verifyPlan(Path staging, PublicationPlan plan) throws IOException {
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

  static void verifyFile(Path path, String expectedSha256) throws IOException {
    ObservedFile observed = observe(path);
    if (!expectedSha256.equals(observed.sha256())) {
      throw new IOException("staged artifact digest mismatch: " + path.getFileName());
    }
  }

  static void verifyWorkload(Path path, PreparedWorkload workload) throws IOException {
    ObservedFile observed = observe(path);
    if (observed.byteCount() != workload.byteCount()
        || observed.dataRows() != workload.recordCount()
        || !observed.sha256().equals(workload.sha256())) {
      throw new IOException("staged workload observation mismatch: " + path.getFileName());
    }
  }

  static void writeWorkload(Path path, PreparedWorkload workload) throws IOException {
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

  private static ObservedFile observe(Path path) throws IOException {
    MessageDigest digest = WorkloadChecksums.sha256Digest();
    long bytes = 0;
    long lineFeeds = 0;
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[SCRATCH_BYTES];
      int length;
      while ((length = input.read(buffer)) >= 0) {
        if (length > 0) {
          digest.update(buffer, 0, length);
          bytes = Math.addExact(bytes, length);
          for (int index = 0; index < length; index++) {
            if (buffer[index] == '\n') lineFeeds = Math.addExact(lineFeeds, 1);
          }
        }
      }
    }
    return new ObservedFile(bytes, lineFeeds < 1 ? -1 : lineFeeds - 1,
        WorkloadChecksums.hex(digest.digest()));
  }

  private record ObservedFile(long byteCount, long dataRows, String sha256) { }
}
