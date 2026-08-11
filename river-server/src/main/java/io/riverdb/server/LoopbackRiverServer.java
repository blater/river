package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TlsChannelBinding;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Single-connection loopback TCP server. Non-loopback binding is deliberately
 * unavailable until authorization and connection admission are implemented.
 */
public final class LoopbackRiverServer {
  private static final int AUTHENTICATION_TIMEOUT_MILLIS = 5_000;
  private static final int IDLE_TIMEOUT_MILLIS = 30_000;
  private final RiverDatabase database;
  private final ServerSocket listener;
  private final TokenAuthenticator authenticator;
  private final SecureRandom random;
  private final byte[] requestBytes = new byte[ProtocolFrameCodec.MAXIMUM_FRAME_BYTES];
  private final byte[] responseBytes = new byte[ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES];
  private final ByteBuffer request = ByteBuffer.wrap(requestBytes);
  private final ByteBuffer response = ByteBuffer.wrap(responseBytes);
  private volatile StatusCode lastStatus = StatusCode.OK;
  private volatile Socket activeSocket;
  private volatile boolean running = true;
  private volatile long acceptedConnections;
  private volatile long completedRequests;
  private volatile long rejectedFrames;
  private volatile long authenticationFailures;
  private Thread worker;

  private LoopbackRiverServer(
      RiverDatabase engineDatabase,
      ServerSocket serverSocket,
      TokenAuthenticator tokenAuthenticator) {
    database = engineDatabase;
    listener = serverSocket;
    authenticator = tokenAuthenticator;
    random = tokenAuthenticator == null ? null : new SecureRandom();
  }

  public static StatusCode start(
      RiverDatabase database,
      int port,
      LoopbackServerOpenResult result) {
    if (database == null || port < 0 || port > 65535 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    try {
      ServerSocket socket = new ServerSocket();
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1);
      return startBound(database, socket, null, result);
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  public static StatusCode startAuthenticated(
      RiverDatabase database,
      int port,
      SSLContext context,
      TokenAuthenticator authenticator,
      LoopbackServerOpenResult result) {
    if (database == null
        || port < 0
        || port > 65535
        || context == null
        || authenticator == null
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    try {
      SSLServerSocket socket = (SSLServerSocket) context
          .getServerSocketFactory()
          .createServerSocket();
      socket.setEnabledProtocols(new String[] {"TLSv1.3"});
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1);
      return startBound(database, socket, authenticator, result);
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  public int port() {
    return listener.getLocalPort();
  }

  public boolean isRunning() {
    return running;
  }

  public StatusCode lastStatus() {
    return lastStatus;
  }

  public long acceptedConnections() {
    return acceptedConnections;
  }

  public long completedRequests() {
    return completedRequests;
  }

  public long rejectedFrames() {
    return rejectedFrames;
  }

  public long authenticationFailures() {
    return authenticationFailures;
  }

  public boolean isAuthenticatedTransport() {
    return authenticator != null;
  }

  public StatusCode close() {
    if (!running) {
      return StatusCode.CLOSED;
    }
    running = false;
    StatusCode status = StatusCode.OK;
    try {
      listener.close();
    } catch (IOException failure) {
      status = StatusCode.IO_FAILURE;
    }
    Socket connection = activeSocket;
    if (connection != null) {
      try {
        connection.close();
      } catch (IOException failure) {
        status = StatusCode.IO_FAILURE;
      }
    }
    try {
      worker.join(5_000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      status = StatusCode.CANCELLED;
    }
    if (worker.isAlive()) {
      status = StatusCode.TIMEOUT;
    }
    if (!status.isOk()) {
      lastStatus = status;
    }
    return status;
  }

  private void run() {
    while (running) {
      try {
        Socket connection = listener.accept();
        activeSocket = connection;
        acceptedConnections++;
        serve(connection);
      } catch (IOException failure) {
        if (running) {
          lastStatus = StatusCode.IO_FAILURE;
        }
      } finally {
        activeSocket = null;
      }
    }
  }

  private void serve(Socket connection) {
    SessionEndpoint endpoint = null;
    long authenticationDeadline = 0;
    try (connection;
        InputStream input = connection.getInputStream();
        OutputStream output = connection.getOutputStream()) {
      connection.setSoTimeout(
          authenticator == null ? IDLE_TIMEOUT_MILLIS : AUTHENTICATION_TIMEOUT_MILLIS);
      if (authenticator == null) {
        endpoint = new SessionEndpoint(database);
      } else {
        if (!(connection instanceof SSLSocket secure)) {
          lastStatus = StatusCode.INVARIANT_BROKEN;
          return;
        }
        secure.setEnabledProtocols(new String[] {"TLSv1.3"});
        secure.startHandshake();
        byte[] binding = new byte[TlsChannelBinding.BINDING_BYTES];
        StatusCode bindingStatus = TlsChannelBinding.export(
            secure.getSession(), binding);
        if (!bindingStatus.isOk()) {
          lastStatus = bindingStatus;
          return;
        }
        long challengeHigh;
        long challengeLow;
        do {
          challengeHigh = random.nextLong();
          challengeLow = random.nextLong();
        } while (challengeHigh == 0 && challengeLow == 0);
        endpoint = new SessionEndpoint(
            database,
            authenticator,
            challengeHigh,
            challengeLow,
            binding);
        authenticationDeadline = System.nanoTime()
            + AUTHENTICATION_TIMEOUT_MILLIS * 1_000_000L;
      }
      while (running && readExact(
          input,
          requestBytes,
          0,
          ProtocolFrameCodec.HEADER_BYTES,
          connection,
          authenticationDeadline)) {
        int payloadBytes = readInt(requestBytes, 24);
        if (payloadBytes < 0 || payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
          rejectedFrames++;
          lastStatus = payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
              ? StatusCode.RESOURCE_EXHAUSTED
              : StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        }
        if (!readExact(
            input,
            requestBytes,
            ProtocolFrameCodec.HEADER_BYTES,
            payloadBytes,
            connection,
            authenticationDeadline)) {
          rejectedFrames++;
          lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        }
        request.position(0);
        request.limit(ProtocolFrameCodec.HEADER_BYTES + payloadBytes);
        StatusCode processed = endpoint.process(request, response);
        if (!processed.isOk()) {
          rejectedFrames++;
          lastStatus = processed;
          break;
        }
        output.write(responseBytes, 0, response.remaining());
        output.flush();
        completedRequests++;
        if (authenticationDeadline != 0 && endpoint.authenticationComplete()) {
          authenticationDeadline = 0;
          connection.setSoTimeout(IDLE_TIMEOUT_MILLIS);
        }
      }
    } catch (SocketTimeoutException timeout) {
      if (running) {
        lastStatus = StatusCode.TIMEOUT;
      }
    } catch (IOException failure) {
      if (running) {
        lastStatus = StatusCode.IO_FAILURE;
      }
    } finally {
      if (endpoint != null) {
        authenticationFailures += endpoint.authenticationFailures();
        StatusCode closed = endpoint.close();
        if (!closed.isOk() && closed != StatusCode.CLOSED) {
          lastStatus = closed;
        }
      }
    }
  }

  private static StatusCode startBound(
      RiverDatabase database,
      ServerSocket socket,
      TokenAuthenticator authenticator,
      LoopbackServerOpenResult result) throws IOException {
    LoopbackRiverServer server = new LoopbackRiverServer(database, socket, authenticator);
    StatusCode completed = result.complete(server);
    if (!completed.isOk()) {
      socket.close();
      return completed;
    }
    server.worker = Thread.ofPlatform()
        .daemon(true)
        .name("river-loopback-server")
        .start(server::run);
    return StatusCode.OK;
  }

  private static boolean readExact(
      InputStream input,
      byte[] target,
      int offset,
      int length,
      Socket connection,
      long deadline) throws IOException {
    int read = 0;
    while (read < length) {
      if (deadline != 0) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new SocketTimeoutException("authentication deadline expired");
        }
        long millis = (remaining + 999_999L) / 1_000_000L;
        connection.setSoTimeout((int) Math.min(Integer.MAX_VALUE, millis));
      }
      int count = input.read(target, offset + read, length - read);
      if (count < 0) {
        return false;
      }
      read += count;
    }
    return true;
  }

  private static int readInt(byte[] source, int offset) {
    return (source[offset] & 0xff) << 24
        | (source[offset + 1] & 0xff) << 16
        | (source[offset + 2] & 0xff) << 8
        | source[offset + 3] & 0xff;
  }
}
