package io.riverdb.cli;

import io.riverdb.base.error.StatusCode;
import java.io.InputStream;
import java.io.PrintStream;

/** Bounded script-oriented SQL client using generated client.properties. */
public final class RiverSqlMain {
  public static final int MAXIMUM_STATEMENT_CHARACTERS = 64 * 1024;

  private RiverSqlMain() {
  }

  public static int run(
      String[] arguments,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    if (arguments != null && arguments.length == 1) {
      return runClientFile(arguments[0], input, output, errors);
    }
    return usage(errors);
  }

  public static int runClientFile(
      String clientFile,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    return RiverSqlConnection.run(clientFile, input, output, errors);
  }

  private static int usage(PrintStream errors) {
    errors.println("usage: river CLIENT_PROPERTIES < script.sql");
    return 2;
  }

  static void report(PrintStream errors, StatusCode status) {
    errors.print("ERROR\t");
    errors.println(status);
  }
}
