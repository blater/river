package io.riverdb.cli;

import io.riverdb.base.error.StatusCode;
import java.io.InputStream;
import java.io.PrintStream;

/** Bounded script-oriented SQL client using generated client.properties. */
public final class RiverSqlMain {
  static final int MAXIMUM_STATEMENT_CHARACTERS = 64 * 1024;

  private RiverSqlMain() {
  }

  public static void main(String[] arguments) {
    int exit = arguments.length == 1
        ? runClientFile(arguments[0], System.in, System.out, System.err)
        : usage(System.err);
    if (exit != 0) {
      System.exit(exit);
    }
  }

  public static int runClientFile(
      String clientFile,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    return RiverSqlConnection.run(clientFile, input, output, errors);
  }

  private static int usage(PrintStream errors) {
    errors.println("usage: river-sql CLIENT_PROPERTIES < script.sql");
    return 2;
  }

  static void report(PrintStream errors, StatusCode status) {
    errors.print("ERROR\t");
    errors.println(status);
  }
}
