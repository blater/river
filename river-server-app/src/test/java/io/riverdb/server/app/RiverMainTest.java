package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

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
  void bareRootKeepsClientUsageAndExitCode() {
    Invocation invocation = invoke();

    assertEquals(2, invocation.exit);
    assertEquals("usage: river CLIENT_PROPERTIES < script.sql\n", invocation.error);
    assertEquals("", invocation.output);
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
