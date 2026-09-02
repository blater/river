package io.riverdb.bench.tpcc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Shared low-overhead JFR setup for the one-transaction diagnostic. */
final class TpccTraceRecording implements AutoCloseable {
  private final Recording recording;
  private final Path target;
  private boolean closed;

  private TpccTraceRecording(Recording active, Path destination) {
    recording = active;
    target = destination;
  }

  static TpccTraceRecording start(Path target, String name)
      throws IOException, ParseException {
    if (target == null) throw new IllegalArgumentException("trace target must not be null");
    Path destination = target.toAbsolutePath();
    if (Files.exists(destination)) {
      throw new IllegalStateException("refusing to overwrite trace " + destination);
    }
    Path parent = destination.getParent();
    if (parent != null) Files.createDirectories(parent);

    Recording active = new Recording(Configuration.getConfiguration("profile"));
    try {
      active.setName(name);
      enablePeriodic(active, "jdk.ExecutionSample", Duration.ofMillis(1), true);
      enablePeriodic(active, "jdk.CPUTimeSample", Duration.ofMillis(1), true);
      enablePeriodic(active, "jdk.ThreadCPULoad", Duration.ofMillis(10), false);
      enable(active, "jdk.SocketRead", Duration.ZERO, true);
      enable(active, "jdk.SocketWrite", Duration.ZERO, true);
      enable(active, "jdk.FileRead", Duration.ZERO, true);
      enable(active, "jdk.FileWrite", Duration.ZERO, true);
      enable(active, "jdk.FileForce", Duration.ZERO, true);
      enable(active, "jdk.ThreadPark", Duration.ZERO, true);
      enable(active, "jdk.JavaMonitorEnter", Duration.ZERO, true);
      enable(active, "jdk.ObjectAllocationInNewTLAB", Duration.ZERO, true);
      enable(active, "jdk.ObjectAllocationOutsideTLAB", Duration.ZERO, true);
      active.start();
      return new TpccTraceRecording(active, destination);
    } catch (RuntimeException failure) {
      active.close();
      throw failure;
    }
  }

  private static void enable(
      Recording recording, String event, Duration threshold, boolean stackTrace) {
    var settings = recording.enable(event).withThreshold(threshold);
    if (stackTrace) settings.withStackTrace();
  }

  private static void enablePeriodic(
      Recording recording, String event, Duration period, boolean stackTrace) {
    var settings = recording.enable(event).withPeriod(period);
    if (stackTrace) settings.withStackTrace();
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      recording.stop();
      recording.dump(target);
    } finally {
      recording.close();
    }
  }
}
