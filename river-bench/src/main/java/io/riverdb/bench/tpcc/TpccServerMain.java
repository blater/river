package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;

/** Owns one temporary database and loopback listener for the TPS shell tool. */
public final class TpccServerMain {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5450_4343_5345_5256L, 0x303030_303030_3031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int MAXIMUM_ACTIVE_TRANSACTIONS = 16;

  private TpccServerMain() {}

  public static void main(String[] arguments) throws Exception {
    ServerArguments configuration = ServerArguments.parse(arguments);
    Files.createDirectories(configuration.directory());

    DatabaseOpenResult opened = new DatabaseOpenResult();
    StatusCode status = EmbeddedRiver.create(
        configuration.directory(), DATABASE, GENERATION, MAXIMUM_ACTIVE_TRANSACTIONS, opened);
    if (!status.isOk()) {
      throw new IllegalStateException("TPS database create failed: " + status);
    }

    LoopbackServerOpenResult listening = new LoopbackServerOpenResult();
    status = LoopbackRiverServer.start(
        opened.database(), configuration.port(), configuration.maximumConnections(), listening);
    if (!status.isOk()) {
      opened.database().close();
      throw new IllegalStateException("TPS loopback server start failed: " + status);
    }

    LoopbackRiverServer server = listening.server();
    TpccTraceRecording recording = null;
    try {
      Files.writeString(
          configuration.readyFile(),
          Integer.toString(server.port()),
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      System.out.println("server_ready=" + server.port());
      System.out.flush();
      if (configuration.traceStartFile() != null) {
        waitFor(configuration.traceStartFile());
      }
      if (configuration.jfr() != null) {
        recording = TpccTraceRecording.start(configuration.jfr(), "river-server-single-update");
        if (configuration.traceStartedFile() != null) {
          Files.writeString(
              configuration.traceStartedFile(), "started\n", StandardCharsets.US_ASCII,
              StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
      }
      if (configuration.stopFile() == null) {
        new CountDownLatch(1).await();
      } else {
        waitFor(configuration.stopFile());
      }
    } finally {
      if (recording != null) recording.close();
      close(server, opened);
    }
  }

  private static void waitFor(Path file) throws InterruptedException {
    while (!Files.exists(file)) Thread.sleep(10);
  }

  private static void close(LoopbackRiverServer server, DatabaseOpenResult opened) {
    StatusCode serverStatus = server.close();
    StatusCode databaseStatus = opened.database().close();
    if (!serverStatus.isOk() || !databaseStatus.isOk()) {
      System.err.println(
          "TPS server shutdown failed: server=" + serverStatus
              + " database=" + databaseStatus);
    }
  }

  private record ServerArguments(
      Path directory, int port, int maximumConnections, Path readyFile,
      Path jfr, Path traceStartFile, Path traceStartedFile, Path stopFile) {
    private static ServerArguments parse(String[] arguments) {
      Path directory = null;
      Path readyFile = null;
      Path jfr = null;
      Path traceStartFile = null;
      Path traceStartedFile = null;
      Path stopFile = null;
      int port = -1;
      int maximumConnections = 16;
      for (String argument : arguments) {
        if (argument.startsWith("--directory=")) {
          directory = Path.of(argument.substring("--directory=".length()));
        } else if (argument.startsWith("--port=")) {
          port = Integer.parseInt(argument.substring("--port=".length()));
        } else if (argument.startsWith("--maximum-connections=")) {
          maximumConnections = Integer.parseInt(
              argument.substring("--maximum-connections=".length()));
        } else if (argument.startsWith("--ready-file=")) {
          readyFile = Path.of(argument.substring("--ready-file=".length()));
        } else if (argument.startsWith("--jfr=")) {
          jfr = Path.of(argument.substring("--jfr=".length()));
        } else if (argument.startsWith("--trace-start-file=")) {
          traceStartFile = Path.of(argument.substring("--trace-start-file=".length()));
        } else if (argument.startsWith("--trace-started-file=")) {
          traceStartedFile = Path.of(argument.substring("--trace-started-file=".length()));
        } else if (argument.startsWith("--stop-file=")) {
          stopFile = Path.of(argument.substring("--stop-file=".length()));
        } else {
          throw new IllegalArgumentException("unknown TPS server argument: " + argument);
        }
      }
      if (directory == null || readyFile == null || port < 0 || port > 65_535
          || maximumConnections < 1
          || maximumConnections > LoopbackRiverServer.MAXIMUM_CONNECTION_LIMIT) {
        throw new IllegalArgumentException("invalid TPS server configuration");
      }
      if (traceStartFile != null && jfr == null) {
        throw new IllegalArgumentException("trace start file requires --jfr");
      }
      if (traceStartedFile != null && jfr == null) {
        throw new IllegalArgumentException("trace started file requires --jfr");
      }
      return new ServerArguments(
          directory, port, maximumConnections, readyFile, jfr,
          traceStartFile, traceStartedFile, stopFile);
    }
  }
}
