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
        if (arguments.length == 2 && RiverCommandCatalog.isHelp(arguments[1])) {
          output.print(RiverdCommandHelp.render("version"));
          return 0;
        }
        return usage(errors);
      }
      if ("cli".equals(command) && arguments.length == 2
          && RiverCommandCatalog.isHelp(arguments[1])) {
        output.print(RiverdCommandHelp.render("cli"));
        return 0;
      }
      if ("help".equals(command) || RiverCommandCatalog.isHelp(command)) {
        if ("help".equals(command) && arguments.length == 2
            && RiverCommandCatalog.isHelp(arguments[1])) {
          output.print(RiverdCommandHelp.render("help"));
          return 0;
        }
        String topic = RiverCommandCatalog.join(arguments, 1);
        if (arguments.length == 1) {
          output.print(RiverdCommandHelp.render(null));
          return 0;
        }
        if (RiverCommandCatalog.rootTopic(topic) == null) {
          errors.print(RiverdCommandHelp.render(null));
          errors.println("unknown help topic: " + topic);
          return 2;
        }
        output.print(RiverdCommandHelp.render(topic));
        return 0;
      }
      if (command.startsWith("-")) return usage(errors);
    }
    return RiverSqlMain.run(arguments, input, output, errors);
  }

  static String usage() {
    return RiverdCommandHelp.render(null);
  }

  private static int usage(PrintStream errors) {
    errors.print(RiverdCommandHelp.render(null));
    return 2;
  }

}
