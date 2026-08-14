package io.riverdb.bench.harness;

import io.riverdb.bench.harness.BenchmarkArtifactDocuments.PublicationPlan;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Prepares and atomically publishes a create-once local benchmark run.
 *
 * <p>The run directory is an exclusive claim and is not itself a completion signal. Readers
 * consider only the atomically installed {@code artifacts/} child, and must validate its
 * {@code result.json} plus referenced digests before accepting the run as complete.
 */
public final class BenchmarkArtifactWriter {
  private final BenchmarkArtifactDocuments documents;
  private final AtomicArtifactPublisher publisher;

  public BenchmarkArtifactWriter() {
    documents = new BenchmarkArtifactDocuments();
    publisher = new AtomicArtifactPublisher();
  }

  public ArtifactWriteResult write(
      Path outputRoot,
      String runId,
      Instant createdAt,
      String riverCommit,
      List<WorkloadArtifact> workloads,
      List<SampleArtifact> samples,
      long highestTrackableNanos,
      int significantDigits) throws IOException {
    PublicationPlan plan = documents.prepareBuffered(
        runId,
        createdAt,
        riverCommit,
        workloads,
        samples,
        highestTrackableNanos,
        significantDigits);
    if (plan.status() != ArtifactWriteStatus.WRITTEN) {
      return new ArtifactWriteResult(plan.status(), null, plan.validation());
    }
    return publisher.publish(outputRoot, runId, plan);
  }

  /** Publishes deterministic table streams without retaining their payloads in heap. */
  public ArtifactWriteResult writeStreaming(
      Path outputRoot,
      String runId,
      Instant createdAt,
      String riverCommit,
      List<StreamingWorkloadArtifact> workloads,
      List<SampleArtifact> samples,
      long highestTrackableNanos,
      int significantDigits) throws IOException {
    ArtifactWriteResult target = publisher.advisoryTarget(outputRoot, runId);
    if (target != null) {
      return target;
    }
    return publisher.publishStreaming(
        outputRoot,
        runId,
        () -> documents.prepareStreaming(
            runId,
            createdAt,
            riverCommit,
            workloads,
            samples,
            highestTrackableNanos,
            significantDigits));
  }
}
