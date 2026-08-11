package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.ProtocolFrameCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Single-connection loopback TCP server. Non-loopback binding is deliberately
 * unavailable until TLS and authentication are implemented.
 */
public final class LoopbackRiverServer {
  private final RiverDatabase database;
  private final ServerSocket listener;
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
  private Thread worker;

  private LoopbackRiverServer(RiverDatabase engineDatabase, ServerSocket serverSocket) {
    database = engineDatabase;
    listener = serverSocket;
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
      LoopbackRiverServer server = new LoopbackRiverServer(database, socket);
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
    SessionEndpoint endpoint = new SessionEndpoint(database);
    try (connection;
        InputStream input = connection.getInputStream();
        OutputStream output = connection.getOutputStream()) {
      while (running && readExact(input, requestBytes, 0, ProtocolFrameCodec.HEADER_BYTES)) {
        int payloadBytes = readInt(requestBytes, 24);
        if (payloadBytes < 0 || payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
          rejectedFrames++;
          lastStatus = payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
              ? StatusCode.RESOURCE_EXHAUSTED
              : StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        }
        if (!readExact(input, requestBytes, ProtocolFrameCodec.HEADER_BYTES, payloadBytes)) {
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
      }
    } catch (IOException failure) {
      if (running) {
        lastStatus = StatusCode.IO_FAILURE;
      }
    } finally {
      StatusCode closed = endpoint.close();
      if (!closed.isOk() && closed != StatusCode.CLOSED) {
        lastStatus = closed;
      }
    }
  }

  private static boolean readExact(
      InputStream input,
      byte[] target,
      int offset,
      int length) throws IOException {
    int read = 0;
    while (read < length) {
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
