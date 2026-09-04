package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.RiverDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** File-signalled quiescent metrics window shared by the managed server and client runner. */
final class TpccPerformanceCapture {
  private TpccPerformanceCapture() {}

  static void begin(TpccConfig config) throws IOException, InterruptedException {
    if (config.metricsStartFile() == null) return;
    request(config.metricsStartFile(), config.metricsStartedFile());
  }

  static void end(TpccConfig config) throws IOException, InterruptedException {
    if (config.metricsStopFile() == null) return;
    request(config.metricsStopFile(), config.metricsStoppedFile());
  }

  static ServerResult serve(
      RiverDatabase database,
      Path start,
      Path started,
      Path stop,
      Path stopped,
      Path abort) throws IOException, InterruptedException {
    if (start == null) return new ServerResult(false, StatusCode.OK, "");
    if (!waitFor(start, abort)) {
      return new ServerResult(true, StatusCode.CANCELLED, "");
    }
    StatusCode status = EmbeddedRiver.beginPerformanceCapture(database);
    publish(started, status);
    if (!status.isOk()) return new ServerResult(true, status, "");
    if (!waitFor(stop, abort)) {
      StatusCode cancelled = EmbeddedRiver.cancelPerformanceCapture(database);
      return new ServerResult(
          true, cancelled.isOk() ? StatusCode.CANCELLED : cancelled, "");
    }
    StringBuilder metrics = new StringBuilder(32 * 1024);
    status = EmbeddedRiver.endPerformanceCapture(database, metrics);
    publish(stopped, status);
    return new ServerResult(true, status, metrics.toString());
  }

  private static void request(Path request, Path response)
      throws IOException, InterruptedException {
    Files.writeString(
        request, "capture\n", StandardCharsets.US_ASCII,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    while (!Files.exists(response)) Thread.sleep(10);
    String value = Files.readString(response, StandardCharsets.US_ASCII).strip();
    StatusCode status;
    try {
      status = StatusCode.valueOf(value);
    } catch (IllegalArgumentException failure) {
      throw new IOException("invalid performance capture response: " + value, failure);
    }
    if (!status.isOk()) {
      throw new IOException("performance capture failed: " + status);
    }
  }

  private static boolean waitFor(Path target, Path abort) throws InterruptedException {
    while (!Files.exists(target)) {
      if (abort != null && Files.exists(abort)) return false;
      Thread.sleep(10);
    }
    return true;
  }

  private static void publish(Path target, StatusCode status) throws IOException {
    Files.writeString(
        target, status.name() + '\n', StandardCharsets.US_ASCII,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }

  record ServerResult(boolean enabled, StatusCode status, String metrics) {}
}
