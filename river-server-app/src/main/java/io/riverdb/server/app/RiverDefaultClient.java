package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Resolves the default client configuration for bare {@code river}. */
final class RiverDefaultClient {
  private RiverDefaultClient() { }

  static int run(
      InputStream input,
      PrintStream output,
      PrintStream errors,
      Path home) {
    RiverDaemonPathSelection.Result paths = new RiverDaemonPathSelection.Result();
    StatusCode status = RiverDaemonPathSelection.resolve(null, null, home, paths);
    if (!status.isOk()) {
      errors.println("Default client configuration could not be resolved: " + status);
      errors.println("Start the default instance with `river server start`.");
      errors.flush();
      return 1;
    }
    Path clientFile = paths.datadir.resolve(RiverDaemonIdentity.SECURITY_NAME)
        .resolve("client.properties");
    if (Files.notExists(clientFile, LinkOption.NOFOLLOW_LINKS)) {
      if (System.console() != null) {
        printMissingUsage(output);
        return 0;
      }
      if (input == null) {
        printMissingError(errors, clientFile, "standard input is unavailable");
        return 1;
      }
      try {
        if (input.read() == -1) {
          printMissingUsage(output);
          return 0;
        }
      } catch (IOException failure) {
        printMissingError(errors, clientFile, "could not inspect standard input: " + failure);
        return 1;
      }
      printMissingError(errors, clientFile, "SQL input was provided");
      return 1;
    }
    return io.riverdb.cli.RiverSqlMain.runClientFile(clientFile.toString(), input, output, errors);
  }

  private static void printMissingUsage(PrintStream output) {
    output.println("No default River server has been configured.");
    output.println("Start the default instance with `river server start`.");
    output.println("Then run SQL with `river < script.sql`.");
    output.println("Use `river help` for commands and options.");
    output.flush();
  }

  private static void printMissingError(PrintStream errors, Path clientFile, String reason) {
    errors.println("Default client configuration not found: " + clientFile);
    errors.println(reason + ".");
    errors.println("Start the default instance with `river server start`.");
    errors.flush();
  }
}
