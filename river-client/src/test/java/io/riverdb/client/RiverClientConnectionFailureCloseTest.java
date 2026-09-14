package io.riverdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import org.junit.jupiter.api.Test;

final class RiverClientConnectionFailureCloseTest {
  @Test
  void failedExchangePreservesFailureAndClosesWhenShutdownInputThrows() {
    FailingShutdownInputSocket socket = new FailingShutdownInputSocket();
    RiverClientConnection connection = connection(socket);

    assertEquals(StatusCode.CORRUPTION, connection.fail(StatusCode.CORRUPTION));

    assertTrue(socket.shutdownInputCalled);
    assertTrue(socket.closeCalled);
    assertTrue(connection.closed);
    assertEquals(StatusCode.CORRUPTION, connection.lastStatus());
  }

  @Test
  void ordinaryCloseKeepsTheExistingSocketClosePath() {
    FailingShutdownInputSocket socket = new FailingShutdownInputSocket();
    RiverClientConnection connection = connection(socket);

    assertEquals(StatusCode.OK, connection.close());

    assertFalse(socket.shutdownInputCalled);
    assertTrue(socket.closeCalled);
  }

  private static RiverClientConnection connection(Socket socket) {
    return new RiverClientConnection(
        socket, new ByteArrayInputStream(new byte[0]), OutputStream.nullOutputStream());
  }

  private static final class FailingShutdownInputSocket extends Socket {
    private boolean shutdownInputCalled;
    private boolean closeCalled;

    @Override
    public void shutdownInput() throws IOException {
      shutdownInputCalled = true;
      throw new IOException("injected shutdownInput failure");
    }

    @Override
    public synchronized void close() {
      closeCalled = true;
    }
  }
}
