package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Parses stop, process-list, and credential-renewal option grammars. */
final class RiverdOperationOptionParser {
  private RiverdOperationOptionParser() { }

  static StatusCode stop(String[] arguments, int argumentLimit, RiverdCommandResult result) {
    result.setHelpTopic("server stop");
    result.setTimeoutMillis(RiverCommandCatalog.DEFAULT_TIMEOUT_MILLIS);
    RiverdCommandOptionSupport.Cursor cursor = cursor(arguments, argumentLimit);
    Set<String> seen = new HashSet<>();
    while (cursor.hasNext()) {
      String argument = cursor.next();
      StatusCode status = stopOption(cursor, argument, result, seen);
      if (!status.isOk()) return status;
    }
    result.complete(RiverdCommand.STOP);
    return StatusCode.OK;
  }

  static StatusCode ps(String[] arguments, int argumentLimit, RiverdCommandResult result) {
    result.setHelpTopic("server ps");
    RiverdCommandOptionSupport.Cursor cursor = cursor(arguments, argumentLimit);
    if (cursor.hasNext()) {
      String argument = cursor.next();
      return RiverdCommandOptionSupport.fail(result,
          argument.startsWith("-") ? "ps accepts no options" : "ps accepts no arguments");
    }
    result.complete(RiverdCommand.PS);
    return StatusCode.OK;
  }

  static StatusCode renew(String[] arguments, int argumentLimit, RiverdCommandResult result) {
    result.setHelpTopic("server credentials renew");
    RiverdCommandOptionSupport.Cursor cursor = cursor(arguments, argumentLimit);
    Set<String> seen = new HashSet<>();
    while (cursor.hasNext()) {
      String argument = cursor.next();
      StatusCode status = renewOption(cursor, argument, result, seen);
      if (!status.isOk()) return status;
    }
    result.complete(RiverdCommand.CREDENTIALS_RENEW_UNAVAILABLE);
    result.fail("credentials renew is not yet available in this milestone");
    return StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private static StatusCode stopOption(
      RiverdCommandOptionSupport.Cursor cursor,
      String argument,
      RiverdCommandResult result,
      Set<String> seen) {
    if (RiverCommandCatalog.DATADIR.shortName.equals(argument)) {
      return datadir(cursor, argument, result, seen);
    }
    if (RiverdCommandOptionSupport.startsWith(argument, RiverCommandCatalog.DATADIR)) {
      Path value = RiverdCommandOptionSupport.path(
          RiverdCommandOptionSupport.valueOf(argument, RiverCommandCatalog.DATADIR));
      if (!seen.add("datadir") || value == null || result.server() != null) {
        return RiverdCommandOptionSupport.fail(result,
            result.server() == null ? "invalid datadir path" : "endpoint conflicts with datadir");
      }
      result.setDatadir(value);
      return StatusCode.OK;
    }
    if (RiverdCommandOptionSupport.startsWith(argument, RiverCommandCatalog.TIMEOUT)) {
      if (!seen.add("timeout")) {
        return RiverdCommandOptionSupport.fail(result, "duplicate timeout option");
      }
      Long timeout = RiverdCommandOptionSupport.duration(
          RiverdCommandOptionSupport.valueOf(argument, RiverCommandCatalog.TIMEOUT));
      if (timeout == null) return RiverdCommandOptionSupport.fail(
          result, "timeout must be positive decimal ms, s, or m");
      result.setTimeoutMillis(timeout);
      return StatusCode.OK;
    }
    if (!argument.startsWith("-")) {
      if (result.server() != null) return RiverdCommandOptionSupport.fail(
          result, "duplicate server selector");
      RiverDaemonEndpoint server = RiverDaemonEndpoint.parse(argument);
      if (server == null) return RiverdCommandOptionSupport.fail(
          result, "server must be 127.0.0.1:PORT, [::1]:PORT, or localhost:PORT");
      if (result.datadir() != null) return RiverdCommandOptionSupport.fail(
          result, "endpoint conflicts with datadir");
      result.setServer(server.toString());
      return StatusCode.OK;
    }
    return RiverdCommandOptionSupport.fail(result, "unknown or misplaced stop option: " + argument);
  }

  private static StatusCode datadir(
      RiverdCommandOptionSupport.Cursor cursor,
      String argument,
      RiverdCommandResult result,
      Set<String> seen) {
    if (!cursor.hasArgument() || !seen.add("datadir")) {
      return RiverdCommandOptionSupport.fail(result, "invalid or duplicate datadir option");
    }
    String raw = cursor.nextArgument();
    Path value = RiverdCommandOptionSupport.path(raw);
    if (raw.startsWith("-") || value == null || result.server() != null) {
      return RiverdCommandOptionSupport.fail(result, result.server() == null
          ? "invalid datadir path" : "endpoint conflicts with datadir");
    }
    result.setDatadir(value);
    return StatusCode.OK;
  }

  private static StatusCode renewOption(
      RiverdCommandOptionSupport.Cursor cursor,
      String argument,
      RiverdCommandResult result,
      Set<String> seen) {
    if (RiverCommandCatalog.DATADIR.shortName.equals(argument)
        || RiverdCommandOptionSupport.startsWith(argument, RiverCommandCatalog.DATADIR)) {
      return datadir(cursor, argument, result, seen);
    }
    return RiverdCommandOptionSupport.fail(
        result, "unknown or misplaced credentials renew option: " + argument);
  }

  private static RiverdCommandOptionSupport.Cursor cursor(String[] arguments, int argumentLimit) {
    return new RiverdCommandOptionSupport.Cursor(arguments, argumentLimit);
  }
}
