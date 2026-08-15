package io.riverdb.cli;

import io.riverdb.base.error.StatusCode;
import java.io.InputStream;
import java.io.PrintStream;
import javax.net.ssl.SSLContext;

/** Bounded script-oriented SQL client for a loopback River server. */
public final class RiverSqlMain {
  static final int MAXIMUM_STATEMENT_CHARACTERS = 64 * 1024;

  private RiverSqlMain() {
  }

  public static void main(String[] arguments) {
    int exit = arguments.length == 1
        ? runPort(arguments[0], System.in, System.out, System.err)
        : arguments.length == 3 && "--tls".equals(arguments[0])
            ? runAuthenticatedFile(
                arguments[1], arguments[2], System.in, System.out, System.err)
            : usage(System.err);
    if (exit != 0) {
      System.exit(exit);
    }
  }

  public static int run(
      int port,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    return RiverSqlConnection.run(port, null, null, 0, input, output, errors);
  }

  public static int runAuthenticated(
      int port,
      SSLContext context,
      byte[] token,
      int tokenBytes,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    if (context == null || token == null) return 2;
    return RiverSqlConnection.run(
        port, context, token, tokenBytes, input, output, errors);
  }

  private static int runPort(
      String portText,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    int port = parsePort(portText);
    return port > 0 ? run(port, input, output, errors) : usage(errors);
  }

  private static int runAuthenticatedFile(
      String portText,
      String tokenFile,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    int port = parsePort(portText);
    if (port <= 0 || tokenFile == null || tokenFile.isEmpty()) return usage(errors);
    return RiverSqlConnection.runTokenFile(
        port, tokenFile, input, output, errors);
  }

  private static int parsePort(String text) {
    if (text == null || text.isEmpty()) {
      return -1;
    }
    int port = 0;
    for (int index = 0; index < text.length(); index++) {
      char digit = text.charAt(index);
      if (digit < '0' || digit > '9') {
        return -1;
      }
      port = port * 10 + digit - '0';
      if (port > 65_535) {
        return -1;
      }
    }
    return port;
  }

  private static int usage(PrintStream errors) {
    errors.println("usage: river-sql PORT < script.sql");
    errors.println("   or: river-sql --tls PORT TOKEN_FILE < script.sql");
    return 2;
  }

  static void report(PrintStream errors, StatusCode status) {
    errors.print("ERROR\t");
    errors.println(status);
  }
}
