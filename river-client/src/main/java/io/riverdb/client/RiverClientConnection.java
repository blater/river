package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.protocol.ProtocolFrame;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolFrameHeader;
import io.riverdb.protocol.ProtocolMessageType;
import io.riverdb.protocol.ProtocolResponse;
import io.riverdb.protocol.auth.TokenProof;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Reusable ordered client connection exposing the same bounded API as the
 * embedded engine. One owning thread may use one active session and query.
 */
public final class RiverClientConnection implements RiverDatabase {
  public static final int MINIMUM_TOKEN_BYTES = TokenProof.MINIMUM_TOKEN_BYTES;
  public static final int MAXIMUM_TOKEN_BYTES = TokenProof.MAXIMUM_TOKEN_BYTES;

  final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  final ProtocolFrame frame = new ProtocolFrame();
  final ProtocolFrameHeader responseHeader = new ProtocolFrameHeader();
  final ProtocolResponse response = new ProtocolResponse();
  ByteBuffer request =
      ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
  byte[] responseBytes = new byte[ProtocolFrameCodec.MAXIMUM_FRAME_BYTES];
  ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
  final RiverClientResultWorkspace results = new RiverClientResultWorkspace();
  private final RiverClientRemoteSession session;
  final RiverClientRemotePrograms programs;
  private final Socket socket;
  final InputStream input;
  final OutputStream output;
  volatile StatusCode lastStatus = StatusCode.OK;
  long nextRequestId = 1;
  long completedRequests;
  long bytesSent;
  long bytesReceived;
  long diagnosticTag;
  long diagnosticStepTag;
  long metricsEpoch;
  boolean responseFullyRead;
  volatile boolean cancelled;
  volatile boolean closed;

  RiverClientConnection(
      Socket connectedSocket,
      InputStream socketInput,
      OutputStream socketOutput) {
    socket = connectedSocket;
    input = socketInput;
    output = socketOutput;
    session = new RiverClientRemoteSession(this);
    programs = new RiverClientRemotePrograms(this);
  }

  public static StatusCode connectAuthenticatedLoopback(
      int port,
      SSLContext context,
      byte[] token,
      int tokenBytes,
      RiverClientOpenResult result) {
    if (context == null
        || token == null
        || tokenBytes < TokenProof.MINIMUM_TOKEN_BYTES
        || tokenBytes > TokenProof.MAXIMUM_TOKEN_BYTES
        || tokenBytes > token.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return connect(port, context, token, tokenBytes, result);
  }

  /** Opens an authenticated connection using the launcher-generated client configuration. */
  public static StatusCode connect(
      RiverClientConfiguration configuration, RiverClientOpenResult result) {
    return RiverClientConnector.connect(configuration, result);
  }

  private static StatusCode connect(
      int port,
      SSLContext context,
      byte[] token,
      int tokenBytes,
      RiverClientOpenResult result) {
    return RiverClientConnector.connect(port, context, token, tokenBytes, result);
  }

  @Override
  public StatusCode createSession(SessionOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (session.isActive()) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = exchange(ProtocolMessageType.OPEN_SESSION, null);
    if (status.isOk()) {
      status = response.status();
    }
    if (status.isOk()) {
      session.resetForOpen();
      status = result.complete(session);
    }
    return status;
  }

  @Override
  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (session.isActive()) {
      return StatusCode.CONFLICT;
    }
    return closeSocket();
  }

  @Override
  public synchronized StatusCode deferTerminalClose(RiverSession unreachable) {
    if (unreachable != session || !session.isActive()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = closeSocket();
    if (status.isOk() || status == StatusCode.CLOSED) {
      session.terminate();
      return StatusCode.OK;
    }
    return status;
  }

  public StatusCode lastStatus() {
    return lastStatus;
  }

  public long completedRequests() {
    return completedRequests;
  }

  public long bytesSent() {
    return bytesSent;
  }

  public long bytesReceived() {
    return bytesReceived;
  }

  /** Closes the transport so a blocked ordered request unwinds on both peers. */
  public StatusCode cancel() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    cancelled = true;
    lastStatus = StatusCode.CANCELLED;
    StatusCode status = closeSocket();
    lastStatus = StatusCode.CANCELLED;
    return status;
  }

  synchronized StatusCode exchange(ProtocolMessageType type, String text) {
    return exchange(type, text, null, null, 0);
  }

  synchronized StatusCode exchangeBinary(
      ProtocolMessageType type,
      byte[] payload,
      int payloadBytes) {
    return exchange(type, null, null, payload, payloadBytes);
  }

  synchronized StatusCode exchange(
      ProtocolMessageType type,
      String text,
      ParameterSet parameters,
      byte[] payload,
      int payloadBytes) {
    return RiverClientExchange.exchange(
        this, type, text, parameters, payload, payloadBytes, 0);
  }

  synchronized StatusCode exchangePrepared(
      ProtocolMessageType type, long handle, ParameterSet parameters) {
    return RiverClientExchange.exchange(
        this, type, null, parameters, null, 0, handle);
  }

  StatusCode fail(StatusCode status) {
    lastStatus = status;
    closeSocket();
    lastStatus = status;
    return status;
  }

  StatusCode closeSocket() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    closed = true;
    try {
      socket.close();
      return StatusCode.OK;
    } catch (IOException failure) {
      lastStatus = StatusCode.IO_FAILURE;
      return StatusCode.IO_FAILURE;
    }
  }

  boolean sessionActive() {
    return session.isActive();
  }

  StatusCode copyCommand(CommandResult target) {
    return results.copyCommand(response, target);
  }

  StatusCode reserveResponseBytes(int required) {
    if (required < 0 || required > ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (required <= responseBytes.length) return StatusCode.OK;
    int capacity = Math.min(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES,
        Math.max(required, responseBytes.length << 1));
    try {
      byte[] grown = Arrays.copyOf(responseBytes, capacity);
      ByteBuffer view = ByteBuffer.wrap(grown);
      responseBytes = grown;
      responseBuffer = view;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode growRequestBytes() {
    if (request.capacity() >= ProtocolFrameCodec.MAXIMUM_REQUEST_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int capacity = Math.min(
        ProtocolFrameCodec.MAXIMUM_REQUEST_BYTES, request.capacity() << 1);
    try {
      request = ByteBuffer.allocate(capacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  static boolean readExact(
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

  static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // The connection never escaped; no more useful status can be returned.
    }
  }

  static boolean validDiagnosticContext(
      long requestedDiagnosticTag,
      long requestedDiagnosticStepTag,
      long requestedMetricsEpoch) {
    return requestedDiagnosticTag == 0
            && requestedDiagnosticStepTag == 0
            && requestedMetricsEpoch == 0
        || requestedDiagnosticTag > 0
            && requestedDiagnosticStepTag >= 0
            && requestedMetricsEpoch > 0;
  }
}
