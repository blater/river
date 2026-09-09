package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.id.DatabaseIncarnation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDefaultClientTest {
  @Test
  void missingDefaultClientNamesPathAndStartHint(@TempDir Path home) {
    Invocation invocation = invoke(home);

    assertEquals(1, invocation.exit);
    assertTrue(invocation.error.contains(
        home.toAbsolutePath().normalize()
            .resolve(".river/default/security/client.properties").toString()));
    assertTrue(invocation.error.contains("river server start"));
  }

  @Test
  void startupSummaryIsHumanReadableAndKeepsIpv6EndpointUnambiguous() {
    Path datadir = Path.of("/tmp/river-default-client-test");
    RiverDaemonIdentityRecords.LockRecord owner = new RiverDaemonIdentityRecords.LockRecord(
        datadir.toString(), 17, 29, 42, 43, "/bin/java", "0123456789abcdef0123456789abcdef");
    RiverDaemonRuntimeRecords.Metadata metadata = new RiverDaemonRuntimeRecords.Metadata(
        datadir.toString(), DatabaseIncarnation.of(17, 29), owner, "::1", 9191, 1,
        "test-version", datadir.resolve("security/client.properties").toString(), null);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    RiverDaemonReadyOutput.printSummary(
        metadata, 16, new PrintStream(bytes, true, StandardCharsets.UTF_8));

    String summary = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(summary.contains("data directory: " + datadir));
    assertTrue(summary.contains("endpoint: [::1]:9191"));
    assertTrue(summary.contains("client configuration: " + datadir.resolve(
        "security/client.properties")));
    assertTrue(summary.contains("maximum connections=16"));
  }

  private static Invocation invoke(Path home) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    int exit = RiverDefaultClient.run(
        new ByteArrayInputStream(new byte[0]),
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8),
        home);
    return new Invocation(exit, output.toString(StandardCharsets.UTF_8),
        error.toString(StandardCharsets.UTF_8));
  }

  private record Invocation(int exit, String output, String error) { }
}
