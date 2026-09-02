package io.riverdb.bench.tpcc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Optional JFR recording scoped to the measured TPC-C interval. */
final class TpccJfrProfile implements AutoCloseable {
  private final Recording recording;
  private final Path target;
  private boolean closed;

  private TpccJfrProfile(Recording active, Path destination) {
    recording = active;
    target = destination;
  }

  static TpccJfrProfile open(TpccConfig config) throws IOException, ParseException {
    if (config.jfr() == null) return new TpccJfrProfile(null, null);
    Path target = config.jfr().toAbsolutePath();
    if (Files.exists(target)) {
      throw new IllegalStateException("refusing to overwrite JFR profile " + target);
    }
    Path parent = target.getParent();
    if (parent != null) Files.createDirectories(parent);
    Recording recording = new Recording(Configuration.getConfiguration("profile"));
    try {
      recording.setName("river-tpcc-measured");
      recording.enable("jdk.ExecutionSample")
          .withPeriod(Duration.ofMillis(10))
          .withStackTrace();
      recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO);
      recording.enable("jdk.ThreadPark").withThreshold(Duration.ZERO);
      recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
      recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
      recording.enable("jdk.FileForce").withThreshold(Duration.ZERO);
      recording.enable("jdk.SocketRead").withThreshold(Duration.ZERO);
      recording.enable("jdk.SocketWrite").withThreshold(Duration.ZERO);
      recording.start();
      return new TpccJfrProfile(recording, target);
    } catch (RuntimeException failure) {
      recording.close();
      throw failure;
    }
  }

  @Override
  public void close() throws IOException {
    if (recording == null || closed) return;
    closed = true;
    try {
      recording.stop();
      recording.dump(target);
    } finally {
      recording.close();
    }
  }
}
