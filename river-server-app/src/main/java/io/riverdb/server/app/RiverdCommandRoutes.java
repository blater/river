package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;

/** Routes server commands through their command-specific option grammars. */
final class RiverdCommandRoutes {
  private RiverdCommandRoutes() { }

  static StatusCode start(String[] arguments, RiverdCommandResult result) {
    StatusCode help = RiverdCommandParser.trailingHelp(arguments, result, "start");
    if (!help.isOk() || result.command() == RiverdCommand.HELP) return help;
    boolean helpAtEnd = arguments.length > 2
        && RiverCommandCatalog.isHelp(arguments[arguments.length - 1]);
    StatusCode parsed = RiverdStartOptionParser.parse(
        arguments, arguments.length - (helpAtEnd ? 1 : 0), result);
    if (!parsed.isOk()) return parsed;
    return helpAtEnd ? RiverdCommandParser.help(result, "start") : StatusCode.OK;
  }

  static StatusCode stop(String[] arguments, RiverdCommandResult result) {
    StatusCode help = RiverdCommandParser.trailingHelp(arguments, result, "stop");
    if (!help.isOk() || result.command() == RiverdCommand.HELP) return help;
    boolean helpAtEnd = arguments.length > 2
        && RiverCommandCatalog.isHelp(arguments[arguments.length - 1]);
    StatusCode parsed = RiverdOperationOptionParser.stop(
        arguments, arguments.length - (helpAtEnd ? 1 : 0), result);
    if (!parsed.isOk()) return parsed;
    return helpAtEnd ? RiverdCommandParser.help(
        result, RiverCommandCatalog.serverTopic("stop")) : StatusCode.OK;
  }

  static StatusCode ps(String[] arguments, RiverdCommandResult result) {
    StatusCode help = RiverdCommandParser.trailingHelp(arguments, result, "ps");
    if (!help.isOk() || result.command() == RiverdCommand.HELP) return help;
    boolean helpAtEnd = arguments.length > 2
        && RiverCommandCatalog.isHelp(arguments[arguments.length - 1]);
    StatusCode parsed = RiverdOperationOptionParser.ps(
        arguments, arguments.length - (helpAtEnd ? 1 : 0), result);
    if (!parsed.isOk()) return parsed;
    return helpAtEnd ? RiverdCommandParser.help(
        result, RiverCommandCatalog.serverTopic("ps")) : StatusCode.OK;
  }

  static StatusCode credentials(String[] arguments, RiverdCommandResult result) {
    if (arguments.length == 2 && RiverCommandCatalog.isHelp(arguments[1])) {
      return RiverdCommandParser.help(result, "server credentials");
    }
    if (arguments.length < 2 || !"renew".equals(arguments[1])) {
      return RiverdCommandParser.fail(result, "credentials requires the renew subcommand");
    }
    String[] tail = RiverdCommandParser.tail(arguments, 1);
    StatusCode help = RiverdCommandParser.trailingHelp(tail, result, "credentials renew");
    if (!help.isOk() || result.command() == RiverdCommand.HELP) return help;
    boolean helpAtEnd = tail.length > 2
        && RiverCommandCatalog.isHelp(tail[tail.length - 1]);
    StatusCode parsed = RiverdOperationOptionParser.renew(
        tail, tail.length - (helpAtEnd ? 1 : 0), result);
    if (helpAtEnd && (parsed.isOk() || parsed == StatusCode.FEATURE_NOT_SUPPORTED)) {
      return RiverdCommandParser.help(result, RiverCommandCatalog.serverTopic("credentials renew"));
    }
    return parsed;
  }
}
