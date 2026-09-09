package io.riverdb.server.app;

import io.riverdb.cli.RiverSqlMain;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;

/** The installed River command composition root. */
public final class RiverMain {
  private RiverMain() { }

  public static void main(String[] arguments) {
    System.exit(run(arguments, System.in, System.out, System.err));
  }

  public static int run(
      String[] arguments,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    if (arguments != null && arguments.length > 0) {
      String command = arguments[0];
      if ("server".equals(command)) {
        return RiverdMain.run(
            Arrays.copyOfRange(arguments, 1, arguments.length), output, errors);
      }
      if ("version".equals(command)) {
        if (arguments.length == 1) {
          output.print(RiverdCommandHelp.version(RiverDaemonVersion.value()));
          return 0;
        }
        return usage(errors);
      }
      if (("help".equals(command) || "-h".equals(command) || "--help".equals(command))
          && arguments.length == 1) {
        output.print(usage());
        return 0;
      }
      if (command.startsWith("-")) return usage(errors);
    }
    return RiverSqlMain.run(arguments, input, output, errors);
  }

  static String usage() {
    return "Usage: river CLIENT_PROPERTIES < script.sql\n"
        + "       river server <command> [options]\n"
        + "       river version\n"
        + "\n"
        + "Run SQL scripts with a generated client configuration, or use `river server --help`\n"
        + "for server commands and options.\n";
  }

  private static int usage(PrintStream errors) {
    errors.print(usage());
    return 2;
  }
}
