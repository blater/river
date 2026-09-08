package io.riverdb.cli;

import io.riverdb.base.error.StatusCode;
import io.riverdb.client.RiverClientConfiguration;
import io.riverdb.client.RiverClientConfigurationResult;
import io.riverdb.client.RiverClientConnection;
import io.riverdb.client.RiverClientOpenResult;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Owns client-file CLI connection admission and cleanup. */
final class RiverSqlConnection {
  private RiverSqlConnection() {}

  static int run(
      String clientFile,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    if (clientFile == null || input == null || output == null || errors == null) {
      return 2;
    }
    final Path path;
    try {
      path = Path.of(clientFile);
    } catch (InvalidPathException failure) {
      return failure(errors, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    if (!path.isAbsolute() || !path.equals(path.normalize())) {
      return failure(errors, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    RiverClientConfigurationResult configurationResult =
        new RiverClientConfigurationResult();
    StatusCode status = RiverClientConfiguration.load(path, configurationResult);
    if (!status.isOk()) return failure(errors, status);
    RiverClientOpenResult connected = new RiverClientOpenResult();
    status = configurationResult.configuration().connect(connected);
    if (!status.isOk()) return failure(errors, status);
    RiverClientConnection client = connected.connection();
    SessionOpenResult opened = new SessionOpenResult();
    status = client.createSession(opened);
    if (!status.isOk()) {
      client.close();
      return failure(errors, status);
    }
    return executeAndClose(client, opened.session(), input, output, errors);
  }

  private static int executeAndClose(
      RiverClientConnection client,
      RiverSession session,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    int exit;
    try {
      exit = new RiverSqlExecution().run(session, input, output, errors);
    } catch (IOException failure) {
      exit = failure(errors, StatusCode.IO_FAILURE);
    }
    StatusCode sessionClose = session.close();
    StatusCode clientClose = client.close();
    if (!sessionClose.isOk()) clientClose = sessionClose;
    return exit == 0 && !clientClose.isOk()
        ? failure(errors, clientClose) : exit;
  }

  private static int failure(PrintStream errors, StatusCode status) {
    RiverSqlMain.report(errors, status);
    return 1;
  }
}
