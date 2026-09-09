package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

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
    if ("start".equals(command)) return parseStart(arguments, result);
    if ("stop".equals(command)) return parseOperation(
        arguments, result, RiverdCommand.STOP, "stop");
    if ("ps".equals(command)) return parseOperation(
        arguments, result, RiverdCommand.PS, "ps");
    if ("credentials".equals(command)) return parseCredentials(arguments, result);
    return fail(result, "unknown server command: " + command);
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

  private static StatusCode parseStart(String[] arguments, RiverdCommandResult result) {
    StatusCode help = trailingHelp(arguments, result, "start");
    if (!help.isOk() || result.command() == RiverdCommand.HELP) return help;
    boolean helpAtEnd = arguments.length > 2
        && RiverCommandCatalog.isHelp(arguments[arguments.length - 1]);
    result.complete(RiverdCommand.START);
    result.setHelpTopic("server start");
    result.setPort(RiverCommandCatalog.DEFAULT_PORT);
    result.setIp("127.0.0.1");
    result.setMaximumConnections(RiverCommandCatalog.DEFAULT_MAXIMUM_CONNECTIONS);
    Set<String> seen = new HashSet<>();
    int argumentLimit = arguments.length - (helpAtEnd ? 1 : 0);
    for (int index = 1; index < argumentLimit; index++) {
      String argument = arguments[index];
      if (RiverCommandCatalog.DATADIR.shortName.equals(argument)) {
        if (index + 1 >= arguments.length) return fail(result, "-D requires PATH");
        if (!seen.add("datadir")) return fail(result, "duplicate datadir option");
        String raw = arguments[++index];
        if (raw.startsWith("-")) return fail(result, "-D requires PATH");
        Path value = path(raw);
        if (value == null) return fail(result, "invalid datadir path");
        result.setDatadir(value);
      } else if (startsWith(argument, RiverCommandCatalog.DATADIR)) {
        if (!seen.add("datadir")) return fail(result, "duplicate datadir option");
        Path value = path(valueOf(argument, RiverCommandCatalog.DATADIR));
        if (value == null) return fail(result, "invalid datadir path");
        result.setDatadir(value);
      } else if (startsWith(argument, RiverCommandCatalog.PORT)) {
        if (!seen.add("port")) return fail(result, "duplicate port option");
        Integer value = decimal(valueOf(argument, RiverCommandCatalog.PORT), 0, 65535);
        if (value == null) return fail(result, "port must be decimal 0..65535");
        result.setPort(value);
      } else if (startsWith(argument, RiverCommandCatalog.IP)) {
        if (!seen.add("ip")) return fail(result, "duplicate ip option");
        String value = valueOf(argument, RiverCommandCatalog.IP);
        if (!"127.0.0.1".equals(value) && !"::1".equals(value)) {
          return fail(result, "ip must be 127.0.0.1 or ::1");
        }
        result.setIp(value);
      } else if (startsWith(argument, RiverCommandCatalog.MAXIMUM_CONNECTIONS)) {
        if (!seen.add("maximum-connections")) {
          return fail(result, "duplicate maximum-connections option");
        }
        Integer value = decimal(valueOf(argument, RiverCommandCatalog.MAXIMUM_CONNECTIONS),
            1, Integer.MAX_VALUE);
        if (value == null) {
          return fail(result, "maximum-connections must be decimal 1..2147483647");
        }
        result.setMaximumConnections(value);
      } else if (startsWith(argument, RiverCommandCatalog.READY_FILE)) {
        if (!seen.add("ready-file")) return fail(result, "duplicate ready-file option");
        Path value = path(valueOf(argument, RiverCommandCatalog.READY_FILE));
        if (value == null) return fail(result, "invalid ready-file path");
        result.setReadyFile(value);
      } else {
        return fail(result, "unknown or misplaced start option: " + argument);
      }
    }
    return helpAtEnd ? help(result, "start") : StatusCode.OK;
  }

  private static StatusCode parseOperation(
      String[] arguments, RiverdCommandResult result, RiverdCommand command, String name) {
    StatusCode help = trailingHelp(arguments, result, name);
    if (!help.isOk() || result.command() == RiverdCommand.HELP) return help;
    boolean helpAtEnd = arguments.length > 2
        && RiverCommandCatalog.isHelp(arguments[arguments.length - 1]);
    result.setHelpTopic(RiverCommandCatalog.serverTopic(name));
    Set<String> seen = new HashSet<>();
    if ("stop".equals(name)) result.setTimeoutMillis(RiverCommandCatalog.DEFAULT_TIMEOUT_MILLIS);
    int argumentLimit = arguments.length - (helpAtEnd ? 1 : 0);
    for (int index = 1; index < argumentLimit; index++) {
      String argument = arguments[index];
      if ("ps".equals(name) && argument.startsWith("-")) {
        return fail(result, "ps accepts no options");
      }
      if (RiverCommandCatalog.DATADIR.shortName.equals(argument)) {
        if (index + 1 >= arguments.length || !seen.add("datadir")) {
          return fail(result, "invalid or duplicate datadir option");
        }
        String raw = arguments[++index];
        Path value = path(raw);
        if (raw.startsWith("-") || value == null || result.server() != null) {
          return fail(result, result.server() == null
              ? "invalid datadir path" : "endpoint conflicts with datadir");
        }
        result.setDatadir(value);
      } else if (startsWith(argument, RiverCommandCatalog.DATADIR)) {
        Path value = path(valueOf(argument, RiverCommandCatalog.DATADIR));
        if (!seen.add("datadir") || value == null || result.server() != null) {
          return fail(result, "invalid or duplicate datadir option");
        }
        result.setDatadir(value);
      } else if ("stop".equals(name) && startsWith(argument, RiverCommandCatalog.TIMEOUT)) {
        if (!seen.add("timeout")) return fail(result, "duplicate timeout option");
        Long timeout = duration(valueOf(argument, RiverCommandCatalog.TIMEOUT));
        if (timeout == null) return fail(result, "timeout must be positive decimal ms, s, or m");
        result.setTimeoutMillis(timeout);
      } else if ("stop".equals(name) && !argument.startsWith("-")) {
        if (result.server() != null) return fail(result, "duplicate server selector");
        RiverDaemonEndpoint server = RiverDaemonEndpoint.parse(argument);
        if (server == null) return fail(result, "server must be 127.0.0.1:PORT, [::1]:PORT, or localhost:PORT");
        if (result.datadir() != null) return fail(result, "endpoint conflicts with datadir");
        result.setServer(server.toString());
      } else if ("ps".equals(name)) {
        return fail(result, argument.startsWith("-")
            ? "ps accepts no options" : "ps accepts no arguments");
      } else {
        return fail(result, "unknown or misplaced " + name + " option: " + argument);
      }
    }
    result.complete(command);
    if (helpAtEnd) return help(result, RiverCommandCatalog.serverTopic(name));
    if (command == RiverdCommand.CREDENTIALS_RENEW_UNAVAILABLE) {
      result.fail(name + " is not yet available in this milestone");
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return StatusCode.OK;
  }

  private static StatusCode parseCredentials(String[] arguments, RiverdCommandResult result) {
    if (arguments.length == 2 && RiverCommandCatalog.isHelp(arguments[1])) {
      return help(result, "server credentials");
    }
    if (arguments.length < 2 || !"renew".equals(arguments[1])) {
      return fail(result, "credentials requires the renew subcommand");
    }
    return parseOperation(tail(arguments, 1), result,
        RiverdCommand.CREDENTIALS_RENEW_UNAVAILABLE, "credentials renew");
  }

  private static StatusCode trailingHelp(
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

  private static StatusCode help(RiverdCommandResult result, String topic) {
    result.complete(RiverdCommand.HELP);
    result.setHelpTopic(topic);
    return StatusCode.OK;
  }

  private static String[] tail(String[] arguments, int count) {
    String[] result = new String[arguments.length - count];
    System.arraycopy(arguments, count, result, 0, result.length);
    return result;
  }

  private static boolean startsWith(String value, RiverCommandCatalog.Option option) {
    return value.startsWith(prefix(option));
  }

  private static String valueOf(String value, RiverCommandCatalog.Option option) {
    return value.substring(prefix(option).length());
  }

  private static String prefix(RiverCommandCatalog.Option option) {
    return option.longName + "=";
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
