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
    for (String command : new String[] {"start", "stop", "ps"}) {
      for (String option : new String[] {"--help", "-h", "--unknown"}) {
        Invocation server = invoke("server", command, option);
        assertEquals(option.equals("--unknown") ? 2 : 0, server.exit);
        assertEquals(server, invoke(command, option));
      }
      assertEquals(invoke("help", "server", command), invoke("help", command));
    }
  }

  @Test
  void stopAliasesShareDefaultAndExplicitSelection(@TempDir Path home) {
    String previous = System.getProperty("user.home");
    System.setProperty("user.home", home.toString());
    try {
      Invocation missing = invoke("server", "stop");
      assertEquals(0, missing.exit);
      assertEquals(missing, invoke("stop"));
      String datadir = "--datadir=" + home.resolve("absent");
      assertEquals(invoke("server", "stop", datadir), invoke("stop", datadir));
      assertEquals(invoke("server", "stop", "localhost:9191", datadir),
          invoke("stop", "localhost:9191", datadir));
    } finally {
      if (previous == null) System.clearProperty("user.home");
      else System.setProperty("user.home", previous);
    }
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
