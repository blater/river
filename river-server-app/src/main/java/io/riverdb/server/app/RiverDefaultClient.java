package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
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
    RiverDaemonPaths.Result paths = new RiverDaemonPaths.Result();
    StatusCode status = RiverDaemonPaths.resolve(null, null, home, paths);
    if (!status.isOk()) {
      errors.println("Default client configuration could not be resolved: " + status);
      errors.println("Start the default instance with `river server start`.");
      errors.flush();
      return 1;
    }
    Path clientFile = paths.datadir.resolve(RiverDaemonIdentity.SECURITY_NAME)
        .resolve("client.properties");
    if (Files.notExists(clientFile, LinkOption.NOFOLLOW_LINKS)) {
      errors.println("Default client configuration not found: " + clientFile);
      errors.println("Start the default instance with `river server start`.");
      errors.flush();
      return 1;
    }
    return io.riverdb.cli.RiverSqlMain.runClientFile(clientFile.toString(), input, output, errors);
  }
}
