package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.auth.TlsChannelBinding;
import io.riverdb.protocol.auth.TokenAuthenticator;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Bounded concurrent loopback TCP server. Non-loopback binding is deliberately
 * unavailable until authorization is implemented.
 */
public final class LoopbackRiverServer {
  public static final int DEFAULT_MAXIMUM_CONNECTIONS = 16;
  public static final int MAXIMUM_CONNECTION_LIMIT = 1_024;

  private static final int AUTHENTICATION_TIMEOUT_MILLIS = 5_000;
  private static final int IDLE_TIMEOUT_MILLIS = 30_000;
  private static final int SHUTDOWN_TIMEOUT_MILLIS = 5_000;

  private final RiverDatabase database;
  private final ServerSocket listener;
  private final TokenAuthenticator authenticator;
  private final SecureRandom random;
  private final ConnectionSlot[] slots;
  private final AtomicInteger activeConnections = new AtomicInteger();
  private final AtomicLong acceptedConnections = new AtomicLong();
  private final AtomicLong completedRequests = new AtomicLong();
  private final AtomicLong rejectedConnections = new AtomicLong();
  private final AtomicLong rejectedFrames = new AtomicLong();
  private final AtomicLong authenticationFailures = new AtomicLong();
  private volatile StatusCode lastStatus = StatusCode.OK;
  private volatile boolean running = true;
  private Thread acceptor;

  private LoopbackRiverServer(
      RiverDatabase engineDatabase,
      ServerSocket serverSocket,
      TokenAuthenticator tokenAuthenticator,
      int maximumConnections) {
    database = engineDatabase;
    listener = serverSocket;
    authenticator = tokenAuthenticator;
    random = tokenAuthenticator == null ? null : new SecureRandom();
    slots = new ConnectionSlot[maximumConnections];
    for (int index = 0; index < slots.length; index++) {
      slots[index] = new ConnectionSlot(index);
    }
  }

  public static StatusCode start(
      RiverDatabase database,
      int port,
      LoopbackServerOpenResult result) {
    return start(database, port, DEFAULT_MAXIMUM_CONNECTIONS, result);
  }

  public static StatusCode start(
      RiverDatabase database,
      int port,
      int maximumConnections,
      LoopbackServerOpenResult result) {
    if (!validStart(database, port, maximumConnections, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    try {
      ServerSocket socket = new ServerSocket();
      socket.bind(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
          maximumConnections);
      return startBound(database, socket, null, maximumConnections, result);
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
    return startAuthenticated(
        database,
        port,
        DEFAULT_MAXIMUM_CONNECTIONS,
        context,
        authenticator,
        result);
  }

  public static StatusCode startAuthenticated(
      RiverDatabase database,
      int port,
      int maximumConnections,
      SSLContext context,
      TokenAuthenticator authenticator,
      LoopbackServerOpenResult result) {
    if (!validStart(database, port, maximumConnections, result)
        || context == null
        || authenticator == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    try {
      SSLServerSocket socket = (SSLServerSocket) context
          .getServerSocketFactory()
          .createServerSocket();
      socket.setEnabledProtocols(new String[] {"TLSv1.3"});
      socket.bind(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
          maximumConnections);
      return startBound(
          database,
          socket,
          authenticator,
          maximumConnections,
          result);
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  public int port() {
    return listener.getLocalPort();
  }

  public int maximumConnections() {
    return slots.length;
  }

  public int activeConnections() {
    return activeConnections.get();
  }

  public boolean isRunning() {
    return running;
  }

  public StatusCode lastStatus() {
    return lastStatus;
  }

  public long acceptedConnections() {
    return acceptedConnections.get();
  }

  public long completedRequests() {
    return completedRequests.get();
  }

  public long rejectedConnections() {
    return rejectedConnections.get();
  }

  public long rejectedFrames() {
    return rejectedFrames.get();
  }

  public long authenticationFailures() {
    return authenticationFailures.get();
  }

  public boolean isAuthenticatedTransport() {
    return authenticator != null;
  }

  public StatusCode close() {
    if (!running) {
      return StatusCode.CLOSED;
    }
    running = false;
    StatusCode status = closeListener();
    long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT_MILLIS * 1_000_000L;
    status = joinUntil(acceptor, deadline, status);
    Thread[] workers = new Thread[slots.length];
    synchronized (this) {
      for (int index = 0; index < slots.length; index++) {
        ConnectionSlot slot = slots[index];
        workers[index] = slot.worker;
        if (slot.socket != null) {
          try {
            slot.socket.close();
          } catch (IOException failure) {
            status = StatusCode.IO_FAILURE;
          }
        }
      }
    }
    for (Thread worker : workers) {
      status = joinUntil(worker, deadline, status);
    }
    if (!status.isOk()) {
      lastStatus = status;
    }
    return status;
  }

  private StatusCode closeListener() {
    try {
      listener.close();
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  private void runAccepts() {
    while (running) {
      try {
        Socket connection = listener.accept();
        ConnectionSlot slot = reserve(connection);
        if (slot == null) {
          rejectedConnections.incrementAndGet();
          lastStatus = StatusCode.RESOURCE_EXHAUSTED;
          connection.close();
        } else {
          acceptedConnections.incrementAndGet();
          Thread worker = Thread.ofVirtual()
              .name("river-connection-" + slot.index)
              .unstarted(slot);
          synchronized (this) {
            slot.worker = worker;
          }
          worker.start();
        }
      } catch (IOException failure) {
        if (running) {
          lastStatus = StatusCode.IO_FAILURE;
        }
      }
    }
  }

  private synchronized ConnectionSlot reserve(Socket connection) {
    if (!running) {
      return null;
    }
    for (ConnectionSlot slot : slots) {
      if (slot.socket == null && slot.worker == null) {
        slot.socket = connection;
        activeConnections.incrementAndGet();
        return slot;
      }
    }
    return null;
  }

  private synchronized void release(ConnectionSlot slot) {
    slot.socket = null;
    slot.worker = null;
    activeConnections.decrementAndGet();
  }

  private void serve(ConnectionSlot slot) {
    Socket connection = slot.socket;
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
          slot.requestBytes,
          0,
          ProtocolFrameCodec.HEADER_BYTES,
          connection,
          authenticationDeadline)) {
        int payloadBytes = readInt(slot.requestBytes, 24);
        if (payloadBytes < 0 || payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
          rejectedFrames.incrementAndGet();
          lastStatus = payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
              ? StatusCode.RESOURCE_EXHAUSTED
              : StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        }
        if (!readExact(
            input,
            slot.requestBytes,
            ProtocolFrameCodec.HEADER_BYTES,
            payloadBytes,
            connection,
            authenticationDeadline)) {
          rejectedFrames.incrementAndGet();
          lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        }
        slot.request.position(0);
        slot.request.limit(ProtocolFrameCodec.HEADER_BYTES + payloadBytes);
        StatusCode processed = endpoint.process(slot.request, slot.response);
        if (!processed.isOk()) {
          rejectedFrames.incrementAndGet();
          lastStatus = processed;
          break;
        }
        output.write(slot.responseBytes, 0, slot.response.remaining());
        output.flush();
        completedRequests.incrementAndGet();
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
        authenticationFailures.addAndGet(endpoint.authenticationFailures());
        StatusCode closed = endpoint.close();
        if (!closed.isOk() && closed != StatusCode.CLOSED) {
          lastStatus = closed;
        }
      }
      release(slot);
    }
  }

  private static boolean validStart(
      RiverDatabase database,
      int port,
      int maximumConnections,
      LoopbackServerOpenResult result) {
    return database != null
        && port >= 0
        && port <= 65_535
        && maximumConnections > 0
        && maximumConnections <= MAXIMUM_CONNECTION_LIMIT
        && result != null;
  }

  private static StatusCode startBound(
      RiverDatabase database,
      ServerSocket socket,
      TokenAuthenticator authenticator,
      int maximumConnections,
      LoopbackServerOpenResult result) throws IOException {
    LoopbackRiverServer server = new LoopbackRiverServer(
        database,
        socket,
        authenticator,
        maximumConnections);
    StatusCode completed = result.complete(server);
    if (!completed.isOk()) {
      socket.close();
      return completed;
    }
    server.acceptor = Thread.ofPlatform()
        .daemon(true)
        .name("river-loopback-acceptor")
        .start(server::runAccepts);
    return StatusCode.OK;
  }

  private static StatusCode joinUntil(
      Thread thread,
      long deadline,
      StatusCode current) {
    if (thread == null) {
      return current;
    }
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      return thread.isAlive() ? StatusCode.TIMEOUT : current;
    }
    try {
      thread.join((remaining + 999_999L) / 1_000_000L);
      return thread.isAlive() ? StatusCode.TIMEOUT : current;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return StatusCode.CANCELLED;
    }
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

  private final class ConnectionSlot implements Runnable {
    private final int index;
    private final byte[] requestBytes = new byte[ProtocolFrameCodec.MAXIMUM_FRAME_BYTES];
    private final byte[] responseBytes = new byte[ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES];
    private final ByteBuffer request = ByteBuffer.wrap(requestBytes);
    private final ByteBuffer response = ByteBuffer.wrap(responseBytes);
    private Socket socket;
    private Thread worker;

    private ConnectionSlot(int slotIndex) {
      index = slotIndex;
    }

    @Override
    public void run() {
      serve(this);
    }
  }
}
