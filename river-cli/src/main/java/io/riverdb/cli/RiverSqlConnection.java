package io.riverdb.cli;

import io.riverdb.base.error.StatusCode;
import io.riverdb.client.RiverClientConnection;
import io.riverdb.client.RiverClientOpenResult;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.net.ssl.SSLContext;

/** Owns plain or authenticated CLI connection admission and cleanup. */
final class RiverSqlConnection {
  private RiverSqlConnection() {}

  static int run(
      int port,
      SSLContext context,
      byte[] token,
      int tokenBytes,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    if (port <= 0 || port > 65_535 || input == null || output == null || errors == null) {
      return 2;
    }
    RiverClientOpenResult connected = new RiverClientOpenResult();
    StatusCode status = context == null
        ? RiverClientConnection.connectLoopback(port, connected)
        : RiverClientConnection.connectAuthenticatedLoopback(
            port, context, token, tokenBytes, connected);
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

  static int runTokenFile(
      int port,
      String tokenFile,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    byte[] token = null;
    try {
      Path path = Path.of(tokenFile);
      long bytes = Files.size(path);
      if (bytes < RiverClientConnection.MINIMUM_TOKEN_BYTES
          || bytes > RiverClientConnection.MAXIMUM_TOKEN_BYTES) {
        return failure(errors, StatusCode.INVALID_EXTERNAL_INPUT);
      }
      token = new byte[(int) bytes];
      readToken(path, token);
      return run(
          port, SSLContext.getDefault(), token, token.length, input, output, errors);
    } catch (IOException | GeneralSecurityException | InvalidPathException failure) {
      return failure(errors, StatusCode.IO_FAILURE);
    } finally {
      if (token != null) Arrays.fill(token, (byte) 0);
    }
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
    StatusCode clientClose = sessionClose.isOk() ? client.close() : sessionClose;
    return exit == 0 && !clientClose.isOk()
        ? failure(errors, clientClose) : exit;
  }

  private static void readToken(Path path, byte[] token) throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      int offset = 0;
      while (offset < token.length) {
        int read = input.read(token, offset, token.length - offset);
        if (read < 0) throw new IOException("token file was truncated");
        offset += read;
      }
      if (input.read() >= 0) throw new IOException("token file grew");
    }
  }

  private static int failure(PrintStream errors, StatusCode status) {
    RiverSqlMain.report(errors, status);
    return 1;
  }
}
