package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolFrameHeader;
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
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Bounded concurrent loopback TCP server. Non-loopback binding waits for a
 * multi-principal credential and administration policy.
 */
public final class LoopbackRiverServer {
  public static final int DEFAULT_MAXIMUM_CONNECTIONS = 16;
  public static final int MAXIMUM_CONNECTION_LIMIT = 1_024;

  private final RiverDatabase database;
  final ServerSocket listener;
  private final TokenAuthenticator authenticator;
  private final SecureRandom random;
  final SecurityAuditLog audit;
  private final int authenticationTimeoutMillis;
  private final int idleTimeoutMillis;
  private final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  final ConnectionSlot[] slots;
  private final AtomicInteger activeConnections = new AtomicInteger();
  private final AtomicLong acceptedConnections = new AtomicLong();
  private final AtomicLong completedRequests = new AtomicLong();
  private final AtomicLong rejectedConnections = new AtomicLong();
  private final AtomicLong rejectedFrames = new AtomicLong();
  private final AtomicLong authenticationFailures = new AtomicLong();
  private final AtomicLong authorizationFailures = new AtomicLong();
  volatile StatusCode lastStatus = StatusCode.OK;
  volatile boolean running = true;
  Thread acceptor;

  private LoopbackRiverServer(
      RiverDatabase engineDatabase,
      ServerSocket serverSocket,
      TokenAuthenticator tokenAuthenticator,
      LoopbackServerLimits limits,
      SecurityAuditLog securityAudit) {
    database = engineDatabase;
    listener = serverSocket;
    authenticator = tokenAuthenticator;
    audit = securityAudit;
    authenticationTimeoutMillis = limits.authenticationTimeoutMillis();
    idleTimeoutMillis = limits.idleTimeoutMillis();
    random = tokenAuthenticator == null ? null : new SecureRandom();
    slots = new ConnectionSlot[limits.maximumConnections()];
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
      Path auditDirectory,
      LoopbackServerLimits limits,
      LoopbackServerOpenResult result) {
    if (limits == null
        || !limits.isValid()
        || !validStart(database, port, limits.maximumConnections(), result)
        || context == null
        || authenticator == null
        || auditDirectory == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    SecurityAuditOpenResult auditResult = new SecurityAuditOpenResult();
    StatusCode status = SecurityAuditLog.open(
        auditDirectory, limits.maximumAuditRecords(), auditResult);
    if (!status.isOk()) {
      return status;
    }
    SecurityAuditLog audit = auditResult.audit();
    try {
      SSLServerSocket socket = (SSLServerSocket) context
          .getServerSocketFactory()
          .createServerSocket();
      socket.setEnabledProtocols(new String[] {"TLSv1.3"});
      socket.bind(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
          limits.maximumConnections());
      return startBound(database, socket, authenticator, limits, audit, result);
    } catch (IOException failure) {
      audit.close();
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

  public long authorizationFailures() {
    return authorizationFailures.get();
  }

  public int auditRecordCount() {
    return audit == null ? 0 : audit.recordCount();
  }

  public boolean isDurablyAudited() {
    return audit != null;
  }

  public boolean isAuthenticatedTransport() {
    return authenticator != null;
  }

  public StatusCode close() {
    if (!running) {
      return StatusCode.CLOSED;
    }
    return LoopbackServerShutdown.close(this);
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
          authenticator == null ? idleTimeoutMillis : authenticationTimeoutMillis);
      LoopbackEndpointOpenResult opened = new LoopbackEndpointOpenResult();
      LoopbackEndpointOpener.open(
          connection,
          database,
          authenticator,
          random,
          audit,
          authenticationTimeoutMillis,
          opened);
      if (!opened.status().isOk()) {
        lastStatus = opened.status();
        return;
      }
      endpoint = opened.endpoint();
      authenticationDeadline = opened.authenticationDeadline();
      serveRequests(
          slot,
          connection,
          endpoint,
          input,
          output,
          authenticationDeadline);
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
        authorizationFailures.addAndGet(endpoint.authorizationFailures());
        StatusCode closed = endpoint.close();
        if (!closed.isOk() && closed != StatusCode.CLOSED) {
          lastStatus = closed;
        }
      }
      release(slot);
    }
  }

  private void serveRequests(
      ConnectionSlot slot,
      Socket connection,
      SessionEndpoint endpoint,
      InputStream input,
      OutputStream output,
      long initialAuthenticationDeadline) throws IOException {
    long authenticationDeadline = initialAuthenticationDeadline;
    while (running) {
      int headerBytes = readExact(
          input,
          slot.requestBytes,
          0,
          ProtocolFrameCodec.HEADER_BYTES,
          connection,
          authenticationDeadline);
      if (headerBytes == 0) {
        return;
      }
      if (headerBytes != ProtocolFrameCodec.HEADER_BYTES) {
        rejectedFrames.incrementAndGet();
        lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
        return;
      }
      slot.request.position(0);
      slot.request.limit(ProtocolFrameCodec.HEADER_BYTES);
      StatusCode headerStatus = codec.inspectRequestHeader(
          slot.request, slot.requestHeader);
      if (!headerStatus.isOk()) {
        rejectedFrames.incrementAndGet();
        lastStatus = headerStatus;
        return;
      }
      int payloadBytes = slot.requestHeader.payloadBytes();
      if (readExact(
          input,
          slot.requestBytes,
          ProtocolFrameCodec.HEADER_BYTES,
          payloadBytes,
          connection,
          authenticationDeadline) != payloadBytes) {
        rejectedFrames.incrementAndGet();
        lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
        return;
      }
      slot.request.position(0);
      slot.request.limit(ProtocolFrameCodec.HEADER_BYTES + payloadBytes);
      StatusCode processed = endpoint.process(slot.request, slot.response);
      if (!processed.isOk()) {
        rejectedFrames.incrementAndGet();
        lastStatus = processed;
        return;
      }
      output.write(slot.responseBytes, 0, slot.response.remaining());
      output.flush();
      completedRequests.incrementAndGet();
      if (authenticationDeadline != 0 && endpoint.authenticationComplete()) {
        authenticationDeadline = 0;
        connection.setSoTimeout(idleTimeoutMillis);
      }
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
    return startBound(
        database,
        socket,
        authenticator,
        LoopbackServerLimits.defaults(maximumConnections),
        null,
        result);
  }

  private static StatusCode startBound(
      RiverDatabase database,
      ServerSocket socket,
      TokenAuthenticator authenticator,
      LoopbackServerLimits limits,
      SecurityAuditLog audit,
      LoopbackServerOpenResult result) throws IOException {
    LoopbackRiverServer server = new LoopbackRiverServer(
        database,
        socket,
        authenticator,
        limits,
        audit);
    StatusCode completed = result.complete(server);
    if (!completed.isOk()) {
      socket.close();
      if (audit != null) {
        audit.close();
      }
      return completed;
    }
    server.acceptor = Thread.ofPlatform()
        .daemon(true)
        .name("river-loopback-acceptor")
        .start(server::runAccepts);
    return StatusCode.OK;
  }

  private static int readExact(
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
        return read;
      }
      read += count;
    }
    return read;
  }

  final class ConnectionSlot implements Runnable {
    final int index;
    private final ProtocolFrameHeader requestHeader = new ProtocolFrameHeader();
    private final byte[] requestBytes = new byte[ProtocolFrameCodec.MAXIMUM_FRAME_BYTES];
    private final byte[] responseBytes = new byte[ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES];
    private final ByteBuffer request = ByteBuffer.wrap(requestBytes);
    private final ByteBuffer response = ByteBuffer.wrap(responseBytes);
    Socket socket;
    Thread worker;

    private ConnectionSlot(int slotIndex) {
      index = slotIndex;
    }

    @Override
    public void run() {
      serve(this);
    }
  }
}
