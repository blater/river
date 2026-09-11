package io.riverdb.server;

import io.riverdb.base.concurrent.MutableCancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.ProtocolMemoryBudget;
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

  private final RiverDatabase database;
  final ServerSocket listener;
  private final TokenAuthenticator authenticator;
  private final SecureRandom random;
  final CredentialValidityFence validityFence;
  private final ProtocolMemoryBudget bufferBudget;
  private final int authenticationTimeoutMillis;
  private final int idleTimeoutMillis;
  final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  final ConnectionSlot[] slots;
  private final AtomicInteger activeConnections = new AtomicInteger();
  private final AtomicLong acceptedConnections = new AtomicLong();
  final AtomicLong completedRequests = new AtomicLong();
  private final AtomicLong rejectedConnections = new AtomicLong();
  final AtomicLong rejectedFrames = new AtomicLong();
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
      CredentialValidityFence credentialValidityFence) {
    database = engineDatabase;
    listener = serverSocket;
    authenticator = tokenAuthenticator;
    validityFence = credentialValidityFence;
    authenticationTimeoutMillis = limits.authenticationTimeoutMillis();
    idleTimeoutMillis = limits.idleTimeoutMillis();
    random = new SecureRandom();
    bufferBudget = ProtocolMemoryBudget.forServer(limits.maximumConnections());
    slots = new ConnectionSlot[limits.maximumConnections()];
    for (int index = 0; index < slots.length; index++) {
      slots[index] = new ConnectionSlot(index);
    }
  }

  public static StatusCode startAuthenticated(
      RiverDatabase database,
      InetAddress bindAddress,
      int port,
      SSLContext context,
      TokenAuthenticator authenticator,
      CredentialValidityFence validityFence,
      LoopbackServerLimits limits,
      LoopbackServerOpenResult result) {
    if (bindAddress == null || !bindAddress.isLoopbackAddress()
        || validityFence == null
        || limits == null
        || !limits.isValid()
        || !validStart(database, port, limits.maximumConnections(), result)
        || context == null
        || authenticator == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    SSLServerSocket socket = null;
    try {
      socket = (SSLServerSocket) context
          .getServerSocketFactory()
          .createServerSocket();
      socket.setEnabledProtocols(new String[] {"TLSv1.3"});
      socket.bind(
          new InetSocketAddress(bindAddress, port),
          limits.maximumConnections());
      return startBound(database, socket, authenticator, limits, validityFence, result);
    } catch (IOException failure) {
      validityFence.close();
      if (socket != null) {
        try {
          socket.close();
        } catch (IOException ignored) {
          // Preserve the original bind/start failure status.
        }
      }
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

  public long retainedProtocolBufferBytes() { return bufferBudget.retainedBytes(); }
  public long maximumProtocolBufferBytes() { return bufferBudget.maximumBytes(); }

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
          lastStatus = StatusCode.RESOURCE_EXHAUSTED;
          rejectedConnections.incrementAndGet();
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

  private void release(ConnectionSlot slot) {
    slot.requests.release();
    StatusCode released = slot.responses.releaseHighWater();
    synchronized (this) {
      slot.socket = null;
      slot.worker = null;
      slot.cancellation.reset();
      activeConnections.decrementAndGet();
    }
    if (!released.isOk()) lastStatus = released;
  }

  private void serve(ConnectionSlot slot) {
    Socket connection = slot.socket;
    SessionEndpoint endpoint = null;
    long authenticationDeadline = 0;
    try (connection;
        InputStream input = connection.getInputStream();
        OutputStream output = connection.getOutputStream()) {
      connection.setSoTimeout(authenticationTimeoutMillis);
      LoopbackEndpointOpenResult opened = new LoopbackEndpointOpenResult();
      LoopbackEndpointOpener.open(
          connection,
          database,
          authenticator,
          validityFence,
          random,
          authenticationTimeoutMillis,
          slot.memory,
          slot.responses,
          slot.cancellation,
          opened);
      if (!opened.status().isOk()) {
        lastStatus = opened.status();
        return;
      }
      endpoint = opened.endpoint();
      authenticationDeadline = opened.authenticationDeadline();
      LoopbackConnectionRequestLoop.serve(
          this,
          slot,
          connection,
          endpoint,
          input,
          output,
          authenticationDeadline,
          idleTimeoutMillis);
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
        StatusCode closed = ServerTerminalSessionCleanup.complete(endpoint);
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
        && ProtocolMemoryBudget.supportsServerConnections(maximumConnections)
        && result != null;
  }

  private static StatusCode startBound(
      RiverDatabase database,
      ServerSocket socket,
      TokenAuthenticator authenticator,
      LoopbackServerLimits limits,
      CredentialValidityFence validityFence,
      LoopbackServerOpenResult result) throws IOException {
    LoopbackRiverServer server;
    try {
      server = new LoopbackRiverServer(
          database,
          socket,
          authenticator,
          limits,
          validityFence);
    } catch (OutOfMemoryError failure) {
      socket.close();
      validityFence.close();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode completed = result.complete(server);
    if (!completed.isOk()) {
      socket.close();
      validityFence.close();
      return completed;
    }
    server.acceptor = Thread.ofPlatform()
        .daemon(true)
        .name("river-loopback-acceptor")
        .start(server::runAccepts);
    return StatusCode.OK;
  }

  final class ConnectionSlot implements Runnable {
    final int index;
    final MutableCancellationToken cancellation = new MutableCancellationToken();
    final ServerConnectionMemory memory = new ServerConnectionMemory(bufferBudget);
    final ProtocolFrameHeader requestHeader = new ProtocolFrameHeader();
    final ServerRequestAssembly requests =
        new ServerRequestAssembly(memory.lease());
    final ServerResponseBuffer responses =
        new ServerResponseBuffer(memory.lease());
    final byte[] requestBytes = new byte[ProtocolFrameCodec.MAXIMUM_FRAME_BYTES];
    final ByteBuffer request = ByteBuffer.wrap(requestBytes);
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
