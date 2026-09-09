package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystemResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystems;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFileResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
final class RiverStopProcessTest {
  @Test
  void listsAndStopsSelectedInstanceThenRestartsCommittedDefault(@TempDir Path home)
      throws Exception {
    home = home.toRealPath();
    Path defaultData = home.resolve(".river/default");
    Path otherData = home.resolve("other");
    try (Server first = start(home); Server second = start(home, "--datadir=" + otherData)) {
      Result sql = invoke(home, "CREATE TABLE stop_test (id BIGINT PRIMARY KEY);"
          + "INSERT INTO stop_test VALUES (7);", defaultData.resolve("security/client.properties").toString());
      assertEquals(0, sql.exit, sql.text);
      Result listing = invoke(home, "", "ps");
      assertEquals(0, listing.exit, listing.text);
      assertTrue(listing.text.contains(first.endpoint()), listing.text);
      assertTrue(listing.text.contains(second.endpoint()), listing.text);
      assertTrue(listing.text.contains("DEFAULT"), listing.text);
      assertTrue(listing.text.contains("yes"), listing.text);
      assertTrue(listing.text.contains(otherData.toString()), listing.text);

      Result invalid = invoke(home, "", "stop", second.endpoint(), "--datadir=" + defaultData);
      assertEquals(2, invalid.exit, invalid.text);
      assertTrue(first.process.isAlive());
      assertTrue(second.process.isAlive());

      Result stopped = invoke(home, "", "stop", second.endpoint());
      assertEquals(0, stopped.exit, stopped.text);
      second.assertExited();
      assertTrue(first.process.isAlive());
      assertFalse(Files.exists(otherData.resolve("runtime.properties")));
      assertTrue(Files.exists(otherData.resolve("instance.properties")));

      Result defaultStop = invoke(home, "", "stop");
      assertEquals(0, defaultStop.exit, defaultStop.text);
      first.assertExited();
    }
    try (Server restarted = start(home)) {
      Result read = invoke(home, "SELECT id FROM stop_test;",
          defaultData.resolve("security/client.properties").toString());
      assertEquals(0, read.exit, read.text);
      assertTrue(read.text.lines().anyMatch("7"::equals), read.text);
      Result stop = invoke(home, "", "server", "stop", restarted.endpoint());
      assertEquals(0, stop.exit, stop.text);
      restarted.assertExited();
    }
    Result empty = invoke(home, "", "ps");
    assertEquals(0, empty.exit, empty.text);
    assertTrue(empty.text.contains("river server start"), empty.text);
    Result absent = invoke(home, "", "stop");
    assertEquals(1, absent.exit, absent.text);
    assertTrue(Files.exists(defaultData.resolve("instance.properties")));
  }

  @Test
  void concurrentStopsFinishWithoutLeavingControlFiles(@TempDir Path home) throws Exception {
    home = home.toRealPath();
    try (Server server = start(home)) {
      Path firstLog = Files.createTempFile(home, "stop-one-", ".log");
      Path secondLog = Files.createTempFile(home, "stop-two-", ".log");
      Process first = launch(home, firstLog, "stop", server.endpoint());
      Process second = launch(home, secondLog, "stop", server.endpoint());
      try {
        assertTrue(first.waitFor(40, TimeUnit.SECONDS));
        assertTrue(second.waitFor(40, TimeUnit.SECONDS));
        String firstText = Files.readString(firstLog);
        String secondText = Files.readString(secondLog);
        assertTrue(first.exitValue() == 0 || second.exitValue() == 0,
            firstText + secondText + Files.readString(server.log));
        // A caller that arrives after completed shutdown has no live owner to join.
        assertTrue(first.exitValue() == 0 || firstText.contains("NOT_OWNER"), firstText);
        assertTrue(second.exitValue() == 0 || secondText.contains("NOT_OWNER"), secondText);
        server.assertExited();
        try (var entries = Files.list(home.resolve(".river/default"))) {
          assertFalse(entries.anyMatch(path -> path.getFileName().toString().contains("stop")));
        }
      } finally {
        finish(first);
        finish(second);
      }
    }
  }

  @Test
  void wrongOwnerRequestCannotStopTheServer(@TempDir Path home) throws Exception {
    home = home.toRealPath();
    try (Server server = start(home)) {
      Path datadir = home.resolve(".river/default").toRealPath();
      Properties runtime = new Properties();
      try (var input = Files.newInputStream(datadir.resolve("runtime.properties"))) {
        runtime.load(input);
      }
      String wrongOwner = "0".repeat(32);
      if (wrongOwner.equals(runtime.getProperty("owner-nonce"))) wrongOwner = "1".repeat(32);
      String request = RiverDaemonStopRequest.encode(
          Long.parseLong(runtime.getProperty("database-incarnation-high")),
          Long.parseLong(runtime.getProperty("database-incarnation-low")),
          wrongOwner, "2".repeat(32), runtime.getProperty("record-sha256"),
          System.currentTimeMillis());
      RiverDaemonFileSystemResult filesystem = new RiverDaemonFileSystemResult();
      assertEquals(StatusCode.OK, RiverDaemonFileSystems.current(filesystem));
      RiverDirectoryResult directory = new RiverDirectoryResult();
      assertEquals(StatusCode.OK, filesystem.fileSystem().openDirectory(datadir, directory));
      RiverFileResult file = new RiverFileResult();
      try {
        assertEquals(StatusCode.OK, directory.directory().createFile("stop.request", file));
        assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.write(
            file.file(), request.getBytes(StandardCharsets.UTF_8)));
        assertEquals(StatusCode.OK, file.file().close());
        assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.force(directory.directory()));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.readString(server.log).contains("stop request rejected")
            && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(Files.readString(server.log).contains("stop request rejected"));
        Result rejected = invoke(home, "", "stop", server.endpoint());
        assertEquals(1, rejected.exit, rejected.text);
        assertTrue(server.process.isAlive());
        assertEquals(request, Files.readString(datadir.resolve("stop.request")));
        assertEquals(StatusCode.OK, directory.directory().removeOwned(
            "stop.request", file.file().identity(), new DirectoryOperationResult()));
        assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.force(directory.directory()));
      } finally {
        if (file.file() != null) file.file().close();
        directory.directory().close();
      }
      Result stopped = invoke(home, "", "stop", server.endpoint());
      assertEquals(0, stopped.exit, stopped.text);
      server.assertExited();
    }
  }

  private static Server start(Path home, String... options) throws Exception {
    Path log = Files.createTempFile(home, "server-", ".log");
    List<String> arguments = new ArrayList<>(List.of("server", "start", "--port=0"));
    arguments.addAll(List.of(options));
    Process process = launch(home, log, arguments.toArray(String[]::new));
    Server server = new Server(process, log);
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (process.isAlive() && System.nanoTime() < deadline) {
      if (Files.readString(log).contains("riverd_status=ready")) return server;
      Thread.sleep(25);
    }
    server.close();
    throw new AssertionError("Server did not become ready: " + Files.readString(log));
  }

  private static Result invoke(Path home, String sql, String... arguments) throws Exception {
    Path log = Files.createTempFile(home, "command-", ".log");
    Process process = launch(home, log, arguments);
    try {
      try (var input = process.getOutputStream()) {
        input.write(sql.getBytes(StandardCharsets.UTF_8));
      }
      assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Command timed out: " + Files.readString(log));
      return new Result(process.exitValue(), Files.readString(log));
    } finally {
      finish(process);
    }
  }

  private static Process launch(Path home, Path log, String... arguments) throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    List<String> command = new ArrayList<>(List.of(java, "--enable-native-access=ALL-UNNAMED",
        "-Xmx1g", "-Duser.home=" + home, "-cp", System.getProperty("river.test.classpath"),
        RiverMain.class.getName()));
    command.addAll(List.of(arguments));
    return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
  }

  private static void finish(Process process) throws InterruptedException {
    if (!process.isAlive()) return;
    process.destroy();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
  }

  private record Result(int exit, String text) { }

  private record Server(Process process, Path log) implements AutoCloseable {
    String endpoint() throws IOException {
      String text = Files.readString(log);
      String port = text.lines().filter(line -> line.startsWith("riverd_listen_port="))
          .findFirst().orElseThrow().substring("riverd_listen_port=".length());
      return "127.0.0.1:" + port;
    }

    void assertExited() throws Exception {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), Files.readString(log));
      assertEquals(0, process.exitValue(), Files.readString(log));
    }

    @Override
    public void close() {
      try {
        finish(process);
      } catch (InterruptedException interrupted) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while closing owned server", interrupted);
      }
    }
  }
}
