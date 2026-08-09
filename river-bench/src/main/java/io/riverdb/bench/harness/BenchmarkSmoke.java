package io.riverdb.bench.harness;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Small local-only harness smoke. It deliberately makes no throughput claim. */
public final class BenchmarkSmoke {
  private static final long HIGHEST_TRACKABLE_NANOS = 10_000_000L;
  private static final int SIGNIFICANT_DIGITS = 3;
  private static final long EXPECTED_INTERVAL_NANOS = 100_000L;

  private BenchmarkSmoke() {
  }

  public static void main(String[] args) {
    int status = run(args);
    if (status != 0) {
      System.exit(status);
    }
  }

  static int run(String[] args) {
    if (args.length != 1 || args[0].isBlank()) {
      System.err.println("usage: BenchmarkSmoke OUTPUT_ROOT");
      return 2;
    }
    final Path outputRoot;
    try {
      outputRoot = Path.of(args[0]);
    } catch (InvalidPathException exception) {
      System.err.println("invalid output path");
      return 2;
    }
    GenerationResult bank = new RiverBankGenerator().generate(0x52_49_56_45_52L, 64);
    GenerationResult papers = new RiverPapersGenerator().generate(0x50_41_50_45_52L, 32);
    if (bank.status() != GenerationResult.Status.GENERATED
        || papers.status() != GenerationResult.Status.GENERATED) {
      System.err.println("fixed workload generation failed");
      return 3;
    }
    LatencyRecorder closed = new LatencyRecorder(
        DriverMode.CLOSED_LOOP, HIGHEST_TRACKABLE_NANOS, SIGNIFICANT_DIGITS, 0);
    LatencyRecorder open = new LatencyRecorder(
        DriverMode.OPEN_LOOP,
        HIGHEST_TRACKABLE_NANOS,
        SIGNIFICANT_DIGITS,
        EXPECTED_INTERVAL_NANOS);
    if (!recordSmoke(closed, open)) {
      System.err.println("fixed latency smoke exceeded its declared bounds");
      return 3;
    }
    LatencyReport closedReport = closed.snapshot();
    LatencyReport openReport = open.snapshot();
    List<SampleArtifact> samples = new ArrayList<>();
    addSamples(samples, bank.artifact().name(), closedReport);
    addSamples(samples, bank.artifact().name(), openReport);
    addSamples(samples, papers.artifact().name(), closedReport);
    addSamples(samples, papers.artifact().name(), openReport);
    String runId = "local-" + Long.toUnsignedString(System.currentTimeMillis())
        + '-' + Long.toUnsignedString(System.nanoTime());
    String commit = System.getenv().getOrDefault("RIVER_COMMIT", "unknown-local-worktree");
    try {
      ArtifactWriteResult result = new BenchmarkArtifactWriter().write(
          outputRoot,
          runId,
          Instant.now(),
          commit,
          List.of(bank.artifact(), papers.artifact()),
          samples,
          HIGHEST_TRACKABLE_NANOS,
          SIGNIFICANT_DIGITS);
      if (result.status() != ArtifactWriteStatus.WRITTEN) {
        System.err.println("benchmark artifact not written: " + result.status());
        return 4;
      }
      System.out.println(result.runDirectory());
      return 0;
    } catch (IOException exception) {
      System.err.println("benchmark artifact I/O failed: " + exception.getMessage());
      return 5;
    }
  }

  private static boolean recordSmoke(LatencyRecorder closed, LatencyRecorder open) {
    for (int operation = 0; operation < 64; operation++) {
      long closedStart = operation * 200_000L;
      long closedDuration = 20_000L + operation * 1_000L;
      if (closed.record(closedStart, closedStart, closedStart + closedDuration)
          != LatencyRecordStatus.RECORDED) {
        return false;
      }
      long intended = operation * EXPECTED_INTERVAL_NANOS;
      long queueDelay = operation % 8 == 0 ? 250_000L : 0;
      long service = operation % 16 == 0 ? 450_000L : 35_000L;
      if (open.record(intended, intended + queueDelay, intended + queueDelay + service)
          != LatencyRecordStatus.RECORDED) {
        return false;
      }
    }
    return true;
  }

  private static void addSamples(
      List<SampleArtifact> samples,
      String workload,
      LatencyReport report) {
    String mode = report.mode() == DriverMode.OPEN_LOOP ? "open_loop" : "closed_loop";
    samples.add(new SampleArtifact(
        workload,
        mode,
        "service",
        report.operationCount(),
        report.expectedIntervalNanos(),
        report.service()));
    if (report.mode() == DriverMode.OPEN_LOOP) {
      samples.add(new SampleArtifact(
          workload,
          mode,
          "scheduled",
          report.operationCount(),
          report.expectedIntervalNanos(),
          report.scheduled()));
      samples.add(new SampleArtifact(
          workload,
          mode,
          "coordinated_omission_corrected_service",
          report.operationCount(),
          report.expectedIntervalNanos(),
          report.coordinatedOmissionCorrectedService()));
    }
  }
}
