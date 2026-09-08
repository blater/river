package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Strict, side-effect-free parser for the installed riverd command contract. */
public final class RiverdCommandParser {
  private static final int DEFAULT_PORT = 9191;
  private static final int DEFAULT_MAXIMUM_CONNECTIONS = 16;
  private static final long DEFAULT_TIMEOUT_MILLIS = 30_000;

  private RiverdCommandParser() { }

  public static StatusCode parse(String[] arguments, RiverdCommandResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (arguments == null) return fail(result, "arguments are required");
    if (arguments.length == 0) {
      result.complete(RiverdCommand.BRIEF_HELP);
      return StatusCode.OK;
    }
    if (arguments.length == 1 && "-h".equals(arguments[0])) {
      result.complete(RiverdCommand.BRIEF_HELP);
      return StatusCode.OK;
    }
    if (arguments.length == 1 && "--help".equals(arguments[0])) {
      result.complete(RiverdCommand.FULL_HELP);
      return StatusCode.OK;
    }
    String command = arguments[0];
    if ("help".equals(command)) return parseHelp(arguments, result);
    if ("version".equals(command)) return parseVersion(arguments, result);
    if ("start".equals(command)) return parseStart(arguments, result);
    if ("stop".equals(command)) return parseUnavailable(
        arguments, result, RiverdCommand.STOP_UNAVAILABLE, "stop");
    if ("ps".equals(command)) return parseUnavailable(
        arguments, result, RiverdCommand.PS_UNAVAILABLE, "ps");
    if ("audit".equals(command)) return parseAudit(arguments, result);
    if ("credentials".equals(command)) return parseCredentials(arguments, result);
    return fail(result, "unknown command: " + command);
  }

  private static StatusCode parseHelp(String[] arguments, RiverdCommandResult result) {
    if (arguments.length == 1) {
      result.complete(RiverdCommand.FULL_HELP);
      result.setHelpTopic("global");
      return StatusCode.OK;
    }
    if (arguments.length > 3) return fail(result, "help accepts one command or group");
    String topic = arguments[1];
    if (!validHelpTopic(topic)) return fail(result, "unknown help topic: " + topic);
    if (arguments.length == 3) {
      if (!("credentials".equals(topic) && "renew".equals(arguments[2]))
          && !("audit".equals(topic) && "archive".equals(arguments[2]))) {
        return fail(result, "unknown help topic: " + arguments[2]);
      }
      topic += " " + arguments[2];
    }
    result.complete(RiverdCommand.FULL_HELP);
    result.setHelpTopic(topic);
    return StatusCode.OK;
  }

  private static StatusCode parseVersion(String[] arguments, RiverdCommandResult result) {
    StatusCode help = trailingHelp(arguments, result, 1, "version");
    if (!help.isOk()) return help;
    if (result.command() == RiverdCommand.BRIEF_HELP
        || result.command() == RiverdCommand.FULL_HELP) return StatusCode.OK;
    if (arguments.length != 1) return fail(result, "version accepts no options");
    result.complete(RiverdCommand.VERSION);
    return StatusCode.OK;
  }

  private static StatusCode parseStart(String[] arguments, RiverdCommandResult result) {
    if (arguments.length > 1 && isHelp(arguments[arguments.length - 1])) {
      if (arguments.length != 2) return fail(result, "start help must be the only trailing argument");
      result.complete("-h".equals(arguments[1]) ? RiverdCommand.BRIEF_HELP : RiverdCommand.FULL_HELP);
      result.setHelpTopic("start");
      return StatusCode.OK;
    }
    result.complete(RiverdCommand.START);
    result.setPort(DEFAULT_PORT);
    result.setIp("127.0.0.1");
    result.setMaximumConnections(DEFAULT_MAXIMUM_CONNECTIONS);
    Set<String> seen = new HashSet<>();
    for (int index = 1; index < arguments.length; index++) {
      String argument = arguments[index];
      if ("-D".equals(argument)) {
        if (index + 1 >= arguments.length) return fail(result, "-D requires PATH");
        if (!seen.add("datadir")) return fail(result, "duplicate datadir option");
        String raw = arguments[++index];
        if (raw.startsWith("-")) return fail(result, "-D requires PATH");
        Path value = path(raw);
        if (value == null) return fail(result, "invalid datadir path");
        result.setDatadir(value);
      } else if (argument.startsWith("--datadir=")) {
        if (!seen.add("datadir")) return fail(result, "duplicate datadir option");
        Path value = path(argument.substring("--datadir=".length()));
        if (value == null) return fail(result, "invalid datadir path");
        result.setDatadir(value);
      } else if (argument.startsWith("--port=")) {
        if (!seen.add("port")) return fail(result, "duplicate port option");
        Integer value = decimal(argument.substring("--port=".length()), 0, 65535);
        if (value == null) return fail(result, "port must be decimal 0..65535");
        result.setPort(value);
      } else if (argument.startsWith("--ip=")) {
        if (!seen.add("ip")) return fail(result, "duplicate ip option");
        String value = argument.substring("--ip=".length());
        if (!"127.0.0.1".equals(value) && !"::1".equals(value)) {
          return fail(result, "ip must be 127.0.0.1 or ::1");
        }
        result.setIp(value);
      } else if (argument.startsWith("--maximum-connections=")) {
        if (!seen.add("maximum-connections")) return fail(result, "duplicate maximum-connections option");
        Integer value = decimal(argument.substring("--maximum-connections=".length()), 1, Integer.MAX_VALUE);
        if (value == null) return fail(result, "maximum-connections must be decimal 1..2147483647");
        result.setMaximumConnections(value);
      } else if (argument.startsWith("--ready-file=")) {
        if (!seen.add("ready-file")) return fail(result, "duplicate ready-file option");
        Path value = path(argument.substring("--ready-file=".length()));
        if (value == null) return fail(result, "invalid ready-file path");
        result.setReadyFile(value);
      } else {
        return fail(result, "unknown or misplaced start option: " + argument);
      }
    }
    return StatusCode.OK;
  }

  private static StatusCode parseUnavailable(
      String[] arguments, RiverdCommandResult result, RiverdCommand command, String name) {
    StatusCode help = trailingHelp(arguments, result, 1, name);
    if (help == StatusCode.OK && (result.command() == RiverdCommand.FULL_HELP
        || result.command() == RiverdCommand.BRIEF_HELP)) return help;
    if (!help.isOk()) return help;
    Set<String> seen = new HashSet<>();
    for (int index = 1; index < arguments.length; index++) {
      String argument = arguments[index];
      if ("-D".equals(argument)) {
        if (index + 1 >= arguments.length || !seen.add("datadir")) {
          return fail(result, "invalid or duplicate datadir option");
        }
        String raw = arguments[++index];
        if (raw.startsWith("-") || path(raw) == null) return fail(result, "invalid datadir path");
      } else if (argument.startsWith("--datadir=")) {
        if (!seen.add("datadir") || path(argument.substring(10)) == null) {
          return fail(result, "invalid or duplicate datadir option");
        }
      } else if ("stop".equals(name) && argument.startsWith("--timeout=")) {
        if (!seen.add("timeout")) return fail(result, "duplicate timeout option");
        Long timeout = duration(argument.substring("--timeout=".length()));
        if (timeout == null) return fail(result, "timeout must be positive decimal ms, s, or m");
        result.setTimeoutMillis(timeout);
      } else {
        return fail(result, "unknown or misplaced " + name + " option: " + argument);
      }
    }
    result.complete(command);
    result.fail(name + " is not yet available in this milestone");
    return StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private static StatusCode parseAudit(String[] arguments, RiverdCommandResult result) {
    if (arguments.length == 2 && isHelp(arguments[1])) {
      result.complete("-h".equals(arguments[1]) ? RiverdCommand.BRIEF_HELP : RiverdCommand.FULL_HELP);
      result.setHelpTopic("audit");
      return StatusCode.OK;
    }
    if (arguments.length < 2 || !"archive".equals(arguments[1])) {
      return fail(result, "audit requires the archive subcommand");
    }
    return parseUnavailable(tail(arguments, 1), result,
        RiverdCommand.AUDIT_ARCHIVE_UNAVAILABLE, "audit archive");
  }

  private static StatusCode parseCredentials(String[] arguments, RiverdCommandResult result) {
    if (arguments.length == 2 && isHelp(arguments[1])) {
      result.complete("-h".equals(arguments[1]) ? RiverdCommand.BRIEF_HELP : RiverdCommand.FULL_HELP);
      result.setHelpTopic("credentials");
      return StatusCode.OK;
    }
    if (arguments.length < 2 || !"renew".equals(arguments[1])) {
      return fail(result, "credentials requires the renew subcommand");
    }
    return parseUnavailable(tail(arguments, 1), result,
        RiverdCommand.CREDENTIALS_RENEW_UNAVAILABLE, "credentials renew");
  }

  private static String[] tail(String[] arguments, int count) {
    String[] result = new String[arguments.length - count];
    System.arraycopy(arguments, count, result, 0, result.length);
    return result;
  }

  private static StatusCode trailingHelp(
      String[] arguments, RiverdCommandResult result, int commandLength, String command) {
    if (arguments.length == commandLength + 1 && isHelp(arguments[commandLength])) {
      result.complete("-h".equals(arguments[commandLength])
          ? RiverdCommand.BRIEF_HELP : RiverdCommand.FULL_HELP);
      result.setHelpTopic(command);
      return StatusCode.OK;
    }
    if (arguments.length > commandLength && isHelp(arguments[commandLength])) {
      return fail(result, "help must be the trailing argument");
    }
    return StatusCode.OK;
  }

  private static boolean isHelp(String value) { return "-h".equals(value) || "--help".equals(value); }

  private static boolean validHelpTopic(String value) {
    return "start".equals(value) || "stop".equals(value) || "ps".equals(value)
        || "audit".equals(value) || "credentials".equals(value) || "version".equals(value);
  }

  private static Path path(String value) {
    if (value == null || value.isEmpty() || !value.equals(value.strip())) return null;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == 0 || character == '*' || character == '?' || character == '\n'
          || character == '\r' || Character.getType(character) == Character.CONTROL) return null;
    }
    try {
      return Path.of(value);
    } catch (InvalidPathException failure) {
      return null;
    }
  }

  private static Integer decimal(String value, int minimum, int maximum) {
    if (value == null || value.isEmpty()) return null;
    long parsed = 0;
    for (int index = 0; index < value.length(); index++) {
      char digit = value.charAt(index);
      if (digit < '0' || digit > '9') return null;
      int valueDigit = digit - '0';
      if (parsed > (maximum - valueDigit) / 10) return null;
      parsed = parsed * 10 + valueDigit;
    }
    return parsed < minimum ? null : (int) parsed;
  }

  private static Long duration(String value) {
    if (value == null || value.length() < 2) return null;
    boolean milliseconds = value.endsWith("ms");
    String suffix = value.substring(value.length() - 1);
    long multiplier = milliseconds ? 1 : "s".equals(suffix) ? 1_000
        : "m".equals(suffix) ? 60_000 : -1;
    int digits = milliseconds ? value.length() - 2 : value.length() - 1;
    if (multiplier < 0 || digits <= 0) return null;
    String number = value.substring(0, digits);
    long parsed = 0;
    for (int index = 0; index < number.length(); index++) {
      char digit = number.charAt(index);
      int valueDigit = digit - '0';
      if (valueDigit < 0 || valueDigit > 9
          || parsed > (Long.MAX_VALUE - valueDigit) / 10) return null;
      parsed = parsed * 10 + valueDigit;
    }
    return parsed <= 0 || parsed > Long.MAX_VALUE / multiplier ? null : parsed * multiplier;
  }

  private static StatusCode fail(RiverdCommandResult result, String diagnostic) {
    result.fail(diagnostic);
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
