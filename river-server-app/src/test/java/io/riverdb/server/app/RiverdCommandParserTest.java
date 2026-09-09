package io.riverdb.server.app;

import static io.riverdb.base.error.StatusCode.FEATURE_NOT_SUPPORTED;
import static io.riverdb.base.error.StatusCode.INVALID_EXTERNAL_INPUT;
import static io.riverdb.base.error.StatusCode.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Focused parser checks for defaults, strict syntax, help, and operation selectors. */
final class RiverdCommandParserTest {
  @Test
  void defaultsAndExplicitStart() {
    RiverdCommandResult result = parse("start");
    assertEquals(RiverdCommand.START, result.command());
    assertEquals(9191, result.port());
    assertEquals("127.0.0.1", result.ip());
    assertEquals(16, result.maximumConnections());

    result = parse("start", "-D", "data", "--port=0", "--ip=::1",
        "--maximum-connections=2147483647", "--ready-file=ready");
    assertEquals(Path.of("data"), result.datadir());
    assertEquals(0, result.port());
    assertEquals("::1", result.ip());
    assertEquals(Integer.MAX_VALUE, result.maximumConnections());
    assertEquals(Path.of("ready"), result.readyFile());
    assertEquals(9191, parse("start", "--port=0009191").port());
  }

  @Test
  void rejectsDuplicatesAndOverflow() {
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("start", "--port=1", "--port=2"));
    assertEquals(INVALID_EXTERNAL_INPUT,
        parseStatus("start", "--maximum-connections=2147483648"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("start", "--port=65536"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("start", "--datadir=a?b"));
  }

  @Test
  void parsesAndNormalizesLoopbackSelectors() {
    RiverdCommandResult stop = parse("stop", "127.0.0.1:9191", "--timeout=1s");
    assertEquals(RiverdCommand.STOP, stop.command());
    assertEquals("127.0.0.1:9191", stop.server());
    assertEquals(1_000, stop.timeoutMillis());
    assertEquals("[::1]:7", parse("stop", "[::1]:7").server());
    assertEquals("127.0.0.1:65535", parse("stop", "localhost:65535").server());
  }

  @Test
  void rejectsInvalidSelectorsAndDatadirConflicts() {
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("stop", "localhost:0"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("stop", "localhost:65536"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("stop", "127.0.0.2:9191"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("stop", "::1:9191"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("stop", "[::1]9191"));
    assertEquals(INVALID_EXTERNAL_INPUT,
        parseStatus("stop", "127.0.0.1:9191", "-D", "data"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("ps", "-D", "data"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("ps", "localhost:9191"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("ps", "localhost:9191", "extra"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("ps", "--timeout=1s"));
  }

  @Test
  void helpFormsMatch() {
    assertEquals(RiverdCommand.HELP, parse().command());
    assertEquals(RiverdCommand.HELP, parse("-h").command());
    assertEquals(RiverdCommand.HELP, parse("help").command());
    assertEquals(RiverdCommand.HELP, parse("--help").command());
    assertEquals("server start", parse("help", "start").helpTopic());
    assertEquals(RiverdCommand.HELP, parse("start", "--help").command());
    assertEquals(RiverdCommand.HELP, parse("version", "-h").command());
    assertEquals(RiverdCommand.HELP, parse("version", "--help").command());

    assertEquals(render("help"), render("--help"));
    assertEquals(render("help", "start"), render("start", "--help"));
    assertEquals(render("help", "server", "stop"),
        render("stop", "127.0.0.1:9191", "--timeout=1s", "--help"));
    assertEquals(render("help", "server", "ps"),
        render("ps", "--help"));
    assertEquals(render("help", "credentials", "renew"),
        render("credentials", "renew", "--help"));
  }

  @Test
  void rejectsInvalidStructureAndMarksLaterCommands() {
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("audit"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("start", "--unknown"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("start", "--help", "--port=1"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("version", "unexpected"));
    assertEquals(OK, parseStatus("stop"));
    assertEquals(OK, parseStatus("stop", "--timeout=1s"));
    assertEquals(OK, parseStatus("ps"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("stop", "--timeout=0s"));
    assertEquals(FEATURE_NOT_SUPPORTED, parseStatus("credentials", "renew"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("credentials", "renew", "unexpected"));
    assertEquals(INVALID_EXTERNAL_INPUT, parseStatus("help", "audit"));
  }

  private static RiverdCommandResult parse(String... arguments) {
    RiverdCommandResult result = new RiverdCommandResult();
    StatusCode status = RiverdCommandParser.parse(arguments, result);
    assertEquals(OK, status, "parser rejected " + Arrays.toString(arguments));
    return result;
  }

  private static StatusCode parseStatus(String... arguments) {
    RiverdCommandResult result = new RiverdCommandResult();
    return RiverdCommandParser.parse(arguments, result);
  }

  private static String render(String... arguments) {
    RiverdCommandResult result = parse(arguments);
    return RiverdCommandHelp.render(result.helpTopic());
  }
}
