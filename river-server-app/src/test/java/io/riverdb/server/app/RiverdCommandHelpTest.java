package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RiverdCommandHelpTest {
  @Test
  void everyRootHelpAliasUsesTheSameOverview() {
    String help = invoke("help").output;
    assertEquals(help, invoke("-h").output);
    assertEquals(help, invoke("--help").output);
    assertTrue(help.contains("CLIENT_PROPERTIES"));
    assertTrue(help.contains("river server <command>"));
    assertTrue(help.contains("river help server"));
  }

  @Test
  void everyTopicAcceptsAllPromisedHelpForms() {
    String[] topics = {"help", "cli", "server", "server start", "server stop", "server ps",
        "server credentials", "server credentials renew", "version", "server version"};
    for (String topic : topics) {
      String[] words = topic.split(" ");
      Invocation[] forms = {
        invoke(concat(new String[] {"help"}, words)),
        invoke(concat(new String[] {"-h"}, words)),
        invoke(concat(new String[] {"--help"}, words)),
        invoke(concat(words, new String[] {"--help"})),
        invoke(concat(words, new String[] {"-h"}))
      };
      String expected = null;
      for (Invocation form : forms) {
        assertEquals(0, form.exit, topic);
        assertTrue(!form.output.isEmpty(), topic);
        if (expected == null) expected = form.output;
        else assertEquals(expected, form.output, topic);
      }
    }
  }

  @Test
  void allTopicsExposeSemanticCoverage() {
    Map<String, String[]> expected = Map.of(
        "cli", new String[] {"client.properties", "stdin", "ROWS", "65536"},
        "server", new String[] {"start", "stop", "ps", "credentials", "version", "9191"},
        "server start", new String[] {"-D", "--datadir=PATH", "--port=PORT", "--ip=ADDRESS",
            "--maximum-connections=N", "--ready-file=PATH", "65535", "127.0.0.1"},
        "server stop", new String[] {"--datadir=PATH", "--timeout=DURATION", "30s", "unavailable"},
        "server ps", new String[] {"no operation options", "unavailable"},
        "server credentials", new String[] {"renew", "stopped instance"},
        "server credentials renew", new String[] {"--datadir=PATH", "stopped instance", "unavailable"},
        "version", new String[] {"protocol version", "no operation options"},
        "help", new String[] {"TOPIC", "credentials renew"});
    for (Map.Entry<String, String[]> entry : expected.entrySet()) {
      String output = RiverdCommandHelp.render(entry.getKey());
      for (String term : entry.getValue()) {
        assertTrue(output.toLowerCase().contains(term.toLowerCase()),
            entry.getKey() + " missing " + term);
      }
    }
  }

  @Test
  void unrelatedOptionsAndUnknownTopicsRemainInvalid() {
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parse("start", "--timeout=1s"));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parse("stop", "--port=1"));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parse("ps", "--datadir=/tmp/river"));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parse("ps", "-D", "/tmp/river"));
    Invocation unknown = invoke("help", "server", "unknown");
    assertEquals(2, unknown.exit);
    assertTrue(unknown.error.contains("unknown help topic"));
  }

  @Test
  void unavailableOperationsDoNotBecomeExecutable() {
    assertEquals(StatusCode.FEATURE_NOT_SUPPORTED, parse("stop"));
    assertEquals(StatusCode.FEATURE_NOT_SUPPORTED, parse("ps"));
    assertEquals(StatusCode.FEATURE_NOT_SUPPORTED, parse("credentials", "renew"));
  }

  private static StatusCode parse(String... arguments) {
    RiverdCommandResult result = new RiverdCommandResult();
    return RiverdCommandParser.parse(arguments, result);
  }

  private static Invocation invoke(String... arguments) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    PrintStream outputStream = new PrintStream(output, true, StandardCharsets.UTF_8);
    PrintStream errorStream = new PrintStream(error, true, StandardCharsets.UTF_8);
    int exit = RiverMain.run(arguments, new ByteArrayInputStream(new byte[0]), outputStream, errorStream);
    return new Invocation(exit, output.toString(StandardCharsets.UTF_8),
        error.toString(StandardCharsets.UTF_8));
  }

  private static String[] concat(String[] first, String[] second) {
    String[] result = new String[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private record Invocation(int exit, String output, String error) { }
}
