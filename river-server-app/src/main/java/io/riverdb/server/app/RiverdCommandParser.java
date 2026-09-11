package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;

/** Strict, side-effect-free parser for the server portion of the River command. */
public final class RiverdCommandParser {
  private RiverdCommandParser() { }

  public static StatusCode parse(String[] arguments, RiverdCommandResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (arguments == null) return fail(result, "arguments are required");
    if (arguments.length == 0) return help(result, "server");
    if (RiverCommandCatalog.isHelp(arguments[0]) || "help".equals(arguments[0])) {
      return parseHelpTopic(arguments, result);
    }
    String command = arguments[0];
    if ("version".equals(command)) return parseVersion(arguments, result);
    if ("start".equals(command)) {
      return parseCommand(arguments, result, RiverdCommand.START, "start", "start");
    }
    if ("stop".equals(command)) {
      return parseCommand(arguments, result, RiverdCommand.STOP, "stop", "server stop");
    }
    if ("ps".equals(command)) {
      return parseCommand(arguments, result, RiverdCommand.PS, "ps", "server ps");
    }
    if ("credentials".equals(command)) return parseCredentials(arguments, result);
    return fail(result, "unknown server command: " + command);
  }

  private static StatusCode parseCommand(
      String[] arguments,
      RiverdCommandResult result,
      RiverdCommand command,
      String helpTopic,
      String trailingHelpTopic) {
    StatusCode help = trailingHelp(arguments, result, helpTopic);
    if (!help.isOk() || result.command() == RiverdCommand.HELP) return help;
    boolean helpAtEnd = arguments.length > 2
        && RiverCommandCatalog.isHelp(arguments[arguments.length - 1]);
    int argumentLimit = arguments.length - (helpAtEnd ? 1 : 0);
    StatusCode parsed = switch (command) {
      case START -> RiverdStartOptionParser.parse(arguments, argumentLimit, result);
      case STOP -> RiverdOperationOptionParser.stop(arguments, argumentLimit, result);
      case PS -> RiverdOperationOptionParser.ps(arguments, argumentLimit, result);
      case CREDENTIALS_RENEW_UNAVAILABLE ->
          RiverdOperationOptionParser.renew(arguments, argumentLimit, result);
      default -> fail(result, "unsupported server command grammar");
    };
    if (!parsed.isOk()) return parsed;
    if (command == RiverdCommand.CREDENTIALS_RENEW_UNAVAILABLE && !helpAtEnd) {
      result.fail("credentials renew is not yet available in this milestone");
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return helpAtEnd ? help(result, trailingHelpTopic) : StatusCode.OK;
  }

  private static StatusCode parseCredentials(
      String[] arguments, RiverdCommandResult result) {
    if (arguments.length == 2 && RiverCommandCatalog.isHelp(arguments[1])) {
      return help(result, "server credentials");
    }
    if (arguments.length < 2 || !"renew".equals(arguments[1])) {
      return fail(result, "credentials requires the renew subcommand");
    }
    return parseCommand(
        tail(arguments, 1), result, RiverdCommand.CREDENTIALS_RENEW_UNAVAILABLE,
        "credentials renew",
        RiverCommandCatalog.serverTopic("credentials renew"));
  }

  private static StatusCode parseHelpTopic(String[] arguments, RiverdCommandResult result) {
    if (arguments.length == 1) return help(result, "server");
    String topic = RiverCommandCatalog.join(arguments, 1);
    String canonical = RiverCommandCatalog.serverTopic(topic);
    if (canonical == null) return fail(result, "unknown help topic: " + topic);
    return help(result, canonical);
  }

  private static StatusCode parseVersion(String[] arguments, RiverdCommandResult result) {
    StatusCode help = trailingHelp(arguments, result, "version");
    if (!help.isOk() || result.command() == RiverdCommand.HELP) return help;
    boolean helpAtEnd = arguments.length > 2
        && RiverCommandCatalog.isHelp(arguments[arguments.length - 1]);
    if (arguments.length - (helpAtEnd ? 1 : 0) != 1) {
      return fail(result, "version accepts no options");
    }
    result.complete(RiverdCommand.VERSION);
    return helpAtEnd ? help(result, "version") : StatusCode.OK;
  }

  static StatusCode trailingHelp(
      String[] arguments, RiverdCommandResult result, String topic) {
    for (int index = 1; index < arguments.length; index++) {
      if (!RiverCommandCatalog.isHelp(arguments[index])) continue;
      if (index != arguments.length - 1) {
        return fail(result, "help must be the trailing argument");
      }
      if (index == 1) return help(result, RiverCommandCatalog.serverTopic(topic));
      return StatusCode.OK;
    }
    return StatusCode.OK;
  }

  static StatusCode help(RiverdCommandResult result, String topic) {
    result.complete(RiverdCommand.HELP);
    result.setHelpTopic(topic);
    return StatusCode.OK;
  }

  static String[] tail(String[] arguments, int count) {
    String[] result = new String[arguments.length - count];
    System.arraycopy(arguments, count, result, 0, result.length);
    return result;
  }

  static StatusCode fail(RiverdCommandResult result, String diagnostic) {
    result.fail(diagnostic);
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
