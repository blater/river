package io.riverdb.bench.harness;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Developer-only streaming generation and publication smoke; it makes no rate claim. */
public final class WorkloadGenerationSmoke {
  private WorkloadGenerationSmoke() {
  }

  public static void main(String[] args) {
    int status = run(args);
    if (status != 0) {
      System.exit(status);
    }
  }

  static int run(String[] args) {
    if (args.length != 1 || args[0].isBlank()) {
      System.err.println("usage: WorkloadGenerationSmoke OUTPUT_ROOT");
      return 2;
    }
    final Path outputRoot;
    try {
      outputRoot = Path.of(args[0]);
    } catch (InvalidPathException exception) {
      System.err.println("invalid output path");
      return 2;
    }
    StreamingWorkloadPlan bank = new RiverBankStreamingGenerator()
        .plan(0x52_49_56_45_52L, RiverBankScale.developerSmoke());
    StreamingWorkloadPlan papers = new RiverPapersStreamingGenerator()
        .plan(0x50_41_50_45_52L, RiverPapersScale.developerSmoke());
    if (bank.status() != StreamingWorkloadPlan.Status.PLANNED
        || papers.status() != StreamingWorkloadPlan.Status.PLANNED) {
      System.err.println("fixed streaming workload plan failed");
      return 3;
    }
    List<StreamingWorkloadArtifact> artifacts = new ArrayList<>();
    artifacts.addAll(bank.artifacts());
    artifacts.addAll(papers.artifacts());
    List<SampleArtifact> samples = List.of(
        sample("riverbank_transactions"), sample("riverpapers_documents"));
    String runId = "local-streaming-" + Long.toUnsignedString(System.currentTimeMillis())
        + '-' + Long.toUnsignedString(System.nanoTime());
    String commit = System.getenv().getOrDefault("RIVER_COMMIT", "unknown-local-worktree");
    try {
      ArtifactWriteResult result = new BenchmarkArtifactWriter().writeStreaming(
          outputRoot,
          runId,
          Instant.now(),
          commit,
          artifacts,
          samples,
          1_000_000,
          3);
      if (result.status() != ArtifactWriteStatus.WRITTEN) {
        System.err.println("streaming workload artifact not written: " + result.status());
        return 4;
      }
      System.out.println(result.runDirectory());
      return 0;
    } catch (IOException exception) {
      System.err.println("streaming workload artifact I/O failed: " + exception.getMessage());
      return 5;
    }
  }

  private static SampleArtifact sample(String workload) {
    return new SampleArtifact(
        workload,
        "closed_loop",
        "service",
        1,
        0,
        new LatencySnapshot(1, 1, 1, 1, 1, 1, 1, 1));
  }
}
