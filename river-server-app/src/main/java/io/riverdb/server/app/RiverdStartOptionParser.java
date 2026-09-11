package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Parses the server-start option grammar and installs its defaults. */
final class RiverdStartOptionParser {
  private RiverdStartOptionParser() { }

  static StatusCode parse(String[] arguments, int argumentLimit, RiverdCommandResult result) {
    result.complete(RiverdCommand.START);
    result.setHelpTopic("server start");
    result.setPort(RiverCommandCatalog.DEFAULT_PORT);
    result.setIp("127.0.0.1");
    result.setMaximumConnections(RiverCommandCatalog.DEFAULT_MAXIMUM_CONNECTIONS);
    RiverdCommandOptionSupport.Cursor cursor =
        new RiverdCommandOptionSupport.Cursor(arguments, argumentLimit);
    Set<String> seen = new HashSet<>();
    while (cursor.hasNext()) {
      String argument = cursor.next();
      StatusCode status = parseOption(cursor, argument, result, seen);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static StatusCode parseOption(
      RiverdCommandOptionSupport.Cursor cursor,
      String argument,
      RiverdCommandResult result,
      Set<String> seen) {
    if (RiverCommandCatalog.DATADIR.shortName.equals(argument)) {
      return datadir(cursor, result, seen);
    }
    if (RiverdCommandOptionSupport.startsWith(argument, RiverCommandCatalog.DATADIR)) {
      if (!seen.add("datadir")) return RiverdCommandOptionSupport.fail(result, "duplicate datadir option");
      Path value = RiverdCommandOptionSupport.path(
          RiverdCommandOptionSupport.valueOf(argument, RiverCommandCatalog.DATADIR));
      if (value == null) return RiverdCommandOptionSupport.fail(result, "invalid datadir path");
      result.setDatadir(value);
      return StatusCode.OK;
    }
    if (RiverdCommandOptionSupport.startsWith(argument, RiverCommandCatalog.PORT)) {
      if (!seen.add("port")) return RiverdCommandOptionSupport.fail(result, "duplicate port option");
      Integer value = RiverdCommandOptionSupport.decimal(
          RiverdCommandOptionSupport.valueOf(argument, RiverCommandCatalog.PORT), 0, 65535);
      if (value == null) return RiverdCommandOptionSupport.fail(
          result, "port must be decimal 0..65535");
      result.setPort(value);
      return StatusCode.OK;
    }
    if (RiverdCommandOptionSupport.startsWith(argument, RiverCommandCatalog.IP)) {
      if (!seen.add("ip")) return RiverdCommandOptionSupport.fail(result, "duplicate ip option");
      String value = RiverdCommandOptionSupport.valueOf(argument, RiverCommandCatalog.IP);
      if (!"127.0.0.1".equals(value) && !"::1".equals(value)) {
        return RiverdCommandOptionSupport.fail(result, "ip must be 127.0.0.1 or ::1");
      }
      result.setIp(value);
      return StatusCode.OK;
    }
    if (RiverdCommandOptionSupport.startsWith(argument, RiverCommandCatalog.MAXIMUM_CONNECTIONS)) {
      if (!seen.add("maximum-connections")) {
        return RiverdCommandOptionSupport.fail(result, "duplicate maximum-connections option");
      }
      Integer value = RiverdCommandOptionSupport.decimal(
          RiverdCommandOptionSupport.valueOf(argument, RiverCommandCatalog.MAXIMUM_CONNECTIONS),
          1, Integer.MAX_VALUE);
      if (value == null) return RiverdCommandOptionSupport.fail(
          result, "maximum-connections must be decimal 1..2147483647");
      result.setMaximumConnections(value);
      return StatusCode.OK;
    }
    if (RiverdCommandOptionSupport.startsWith(argument, RiverCommandCatalog.READY_FILE)) {
      if (!seen.add("ready-file")) {
        return RiverdCommandOptionSupport.fail(result, "duplicate ready-file option");
      }
      Path value = RiverdCommandOptionSupport.path(
          RiverdCommandOptionSupport.valueOf(argument, RiverCommandCatalog.READY_FILE));
      if (value == null) return RiverdCommandOptionSupport.fail(result, "invalid ready-file path");
      result.setReadyFile(value);
      return StatusCode.OK;
    }
    return RiverdCommandOptionSupport.fail(result, "unknown or misplaced start option: " + argument);
  }

  private static StatusCode datadir(
      RiverdCommandOptionSupport.Cursor cursor,
      RiverdCommandResult result,
      Set<String> seen) {
    if (!cursor.hasArgument()) return RiverdCommandOptionSupport.fail(result, "-D requires PATH");
    if (!seen.add("datadir")) return RiverdCommandOptionSupport.fail(result, "duplicate datadir option");
    String raw = cursor.nextArgument();
    if (raw.startsWith("-")) return RiverdCommandOptionSupport.fail(result, "-D requires PATH");
    Path value = RiverdCommandOptionSupport.path(raw);
    if (value == null) return RiverdCommandOptionSupport.fail(result, "invalid datadir path");
    result.setDatadir(value);
    return StatusCode.OK;
  }
}
