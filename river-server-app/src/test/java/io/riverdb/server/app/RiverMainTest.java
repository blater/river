package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverMainTest {
  @Test
  void rootHelpFormsRenderOneOverview() {
    Invocation help = invoke("help");
    Invocation shortHelp = invoke("-h");
    Invocation longHelp = invoke("--help");

    assertEquals(0, help.exit);
    assertEquals(help.output, shortHelp.output);
    assertEquals(help.output, longHelp.output);
  }

  @Test
  void bareRootUsesDefaultClientAndSuggestsStartingIt(@TempDir Path home) {
    String previous = System.getProperty("user.home");
    System.setProperty("user.home", home.toString());
    Invocation invocation;
    try {
      invocation = invoke();
    } finally {
      if (previous == null) System.clearProperty("user.home");
      else System.setProperty("user.home", previous);
    }

    assertEquals(0, invocation.exit);
    assertTrue(invocation.output.contains("river server start"));
    assertEquals("", invocation.error);
  }

  @Test
  void rootVersionUsesTheServerDistributionVersionOwner() {
    Invocation root = invoke("version");
    Invocation server = invoke("server", "version");

    assertEquals(0, root.exit);
    assertEquals(0, server.exit);
    assertEquals(server.output, root.output);
  }

  @Test
  void serverArgumentsAndHelpRemainOwnedByTheServerParser() {
    Invocation help = invoke("server", "--help");
    Invocation invalid = invoke("server", "start", "--unknown");

    assertEquals(0, help.exit);
    assertEquals(2, invalid.exit);
  }

  @Test
  void rootOperationAliasesShareServerHelp() {
    Invocation rootStop = invoke("stop", "--help");
    Invocation serverStop = invoke("server", "stop", "--help");
    Invocation rootPs = invoke("ps", "--help");
    Invocation serverPs = invoke("server", "ps", "--help");

    assertEquals(0, rootStop.exit);
    assertEquals(rootStop.output, serverStop.output);
    assertEquals(0, rootPs.exit);
    assertEquals(rootPs.output, serverPs.output);
  }

  private static Invocation invoke(String... arguments) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    PrintStream outputStream = new PrintStream(output, true, StandardCharsets.UTF_8);
    PrintStream errorStream = new PrintStream(error, true, StandardCharsets.UTF_8);
    int exit = RiverMain.run(
        arguments,
        new ByteArrayInputStream(new byte[0]),
        outputStream,
        errorStream);
    return new Invocation(
        exit,
        output.toString(StandardCharsets.UTF_8),
        error.toString(StandardCharsets.UTF_8));
  }

  private record Invocation(int exit, String output, String error) { }
}
