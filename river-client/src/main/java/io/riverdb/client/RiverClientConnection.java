package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.protocol.ProtocolFrame;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import io.riverdb.protocol.ProtocolResponse;
import io.riverdb.protocol.auth.TokenProof;
import io.riverdb.protocol.auth.TlsChannelBinding;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Reusable ordered client connection exposing the same bounded API as the
 * embedded engine. One owning thread may use one active session and query.
 */
public final class RiverClientConnection implements RiverDatabase {
  private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
  private static final int READ_TIMEOUT_MILLIS = 30_000;

  private final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  private final ProtocolFrame frame = new ProtocolFrame();
  private final ProtocolResponse response = new ProtocolResponse();
  private final ByteBuffer request =
      ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
  private final byte[] responseBytes = new byte[ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES];
  private final ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
  private final long[] values = new long[CommandResult.MAXIMUM_COLUMNS];
  private final RemoteSession session = new RemoteSession();
  private final Socket socket;
  private final InputStream input;
  private final OutputStream output;
  private StatusCode lastStatus = StatusCode.OK;
  private long nextRequestId = 1;
  private long completedRequests;
  private long bytesSent;
  private long bytesReceived;
  private boolean sessionActive;
  private boolean closed;

  private RiverClientConnection(
      Socket connectedSocket,
      InputStream socketInput,
      OutputStream socketOutput) {
    socket = connectedSocket;
    input = socketInput;
    output = socketOutput;
  }

  public static StatusCode connectLoopback(int port, RiverClientOpenResult result) {
    return connect(port, null, null, 0, result);
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

  private static StatusCode connect(
      int port,
      SSLContext context,
      byte[] token,
      int tokenBytes,
      RiverClientOpenResult result) {
    if (port <= 0 || port > 65535 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    Socket socket = null;
    byte[] proof = null;
    byte[] channelBinding = null;
    try {
      socket = context == null
          ? new Socket()
          : context.getSocketFactory().createSocket();
      socket.connect(
          new InetSocketAddress("localhost", port),
          CONNECT_TIMEOUT_MILLIS);
      socket.setSoTimeout(READ_TIMEOUT_MILLIS);
      if (socket instanceof SSLSocket secure) {
        secure.setEnabledProtocols(new String[] {"TLSv1.3"});
        SSLParameters parameters = secure.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        secure.setSSLParameters(parameters);
        secure.startHandshake();
        channelBinding = new byte[TlsChannelBinding.BINDING_BYTES];
        StatusCode bindingStatus = TlsChannelBinding.export(
            secure.getSession(), channelBinding);
        if (!bindingStatus.isOk()) {
          closeQuietly(socket);
          return bindingStatus;
        }
      }
      RiverClientConnection connection = new RiverClientConnection(
          socket,
          socket.getInputStream(),
          socket.getOutputStream());
      StatusCode status = connection.exchange(ProtocolMessageType.HELLO, null);
      if (status.isOk()) {
        status = connection.response.status();
      }
      if (status.isOk() && context != null) {
        proof = new byte[TokenProof.PROOF_BYTES];
        status = TokenProof.compute(
            token,
            tokenBytes,
            connection.response.challengeHigh(),
            connection.response.challengeLow(),
            channelBinding,
            proof);
        if (status.isOk()) {
          status = connection.exchangeBinary(
              ProtocolMessageType.AUTHENTICATE,
              proof,
              proof.length);
        }
        if (status.isOk()) {
          status = connection.response.status();
        }
      }
      if (!status.isOk()) {
        connection.fail(status);
        return status;
      }
      status = result.complete(connection);
      if (!status.isOk()) {
        connection.closeSocket();
      }
      return status;
    } catch (IOException failure) {
      if (socket != null) {
        closeQuietly(socket);
      }
      return StatusCode.IO_FAILURE;
    } finally {
      if (proof != null) {
        Arrays.fill(proof, (byte) 0);
      }
      if (channelBinding != null) {
        Arrays.fill(channelBinding, (byte) 0);
      }
    }
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
    if (sessionActive) {
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
    if (status.isOk()) {
      sessionActive = true;
    }
    return status;
  }

  @Override
  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (sessionActive) {
      return StatusCode.CONFLICT;
    }
    return closeSocket();
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

  private synchronized StatusCode exchange(ProtocolMessageType type, String text) {
    return exchange(type, text, null, 0);
  }

  private synchronized StatusCode exchangeBinary(
      ProtocolMessageType type,
      byte[] payload,
      int payloadBytes) {
    return exchange(type, null, payload, payloadBytes);
  }

  private StatusCode exchange(
      ProtocolMessageType type,
      String text,
      byte[] payload,
      int payloadBytes) {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (nextRequestId <= 0 || nextRequestId == Long.MAX_VALUE) {
      return fail(StatusCode.FENCED);
    }
    long requestId = nextRequestId;
    StatusCode status;
    if (payload != null) {
      status = codec.encodeBinaryRequest(request, type, requestId, payload, payloadBytes);
    } else if (text != null) {
      status = codec.encodeTextRequest(request, type, requestId, text);
    } else {
      status = codec.encodeRequest(request, type, requestId);
    }
    if (!status.isOk()) {
      return status;
    }
    try {
      int requestBytes = request.remaining();
      try {
        output.write(request.array(), 0, requestBytes);
        output.flush();
      } finally {
        if (payload != null) {
          Arrays.fill(
              request.array(),
              ProtocolFrameCodec.HEADER_BYTES,
              ProtocolFrameCodec.HEADER_BYTES + payloadBytes,
              (byte) 0);
        }
      }
      bytesSent += requestBytes;
      if (!readExact(input, responseBytes, 0, ProtocolFrameCodec.HEADER_BYTES)) {
        return fail(StatusCode.IO_FAILURE);
      }
      int responsePayloadBytes = readInt(responseBytes, 24);
      if (responsePayloadBytes < 0
          || responsePayloadBytes > ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES
              - ProtocolFrameCodec.HEADER_BYTES) {
        return fail(StatusCode.CORRUPTION);
      }
      if (!readExact(
          input,
          responseBytes,
          ProtocolFrameCodec.HEADER_BYTES,
          responsePayloadBytes)) {
        return fail(StatusCode.IO_FAILURE);
      }
      responseBuffer.position(0);
      responseBuffer.limit(ProtocolFrameCodec.HEADER_BYTES + responsePayloadBytes);
      status = codec.decodeResponse(responseBuffer, frame, response);
      if (!status.isOk()
          || frame.type() != type
          || frame.requestId() != requestId) {
        return fail(StatusCode.CORRUPTION);
      }
      nextRequestId++;
      completedRequests++;
      bytesReceived += ProtocolFrameCodec.HEADER_BYTES + responsePayloadBytes;
      lastStatus = response.status();
      return StatusCode.OK;
    } catch (IOException failure) {
      return fail(StatusCode.IO_FAILURE);
    }
  }

  private StatusCode fail(StatusCode status) {
    lastStatus = status;
    closeSocket();
    lastStatus = status;
    return status;
  }

  private StatusCode closeSocket() {
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

  private void sessionClosed() {
    sessionActive = false;
  }

  private StatusCode copyCommand(CommandResult target) {
    int columns = response.columnCount();
    for (int index = 0; index < columns; index++) {
      values[index] = response.valueAt(index);
    }
    return target.complete(
        response.affectedRows(),
        response.commitSequence(),
        response.transactionActive(),
        response.rowAvailable(),
        response.key(),
        values,
        columns);
  }

  private final class RemoteSession implements RiverSession {
    private final RemoteQuery query = new RemoteQuery();
    private boolean active;

    @Override
    public StatusCode execute(String sql, CommandResult result) {
      if (result == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (!active) {
        return StatusCode.CLOSED;
      }
      if (query.active) {
        return StatusCode.CONFLICT;
      }
      StatusCode status = exchange(ProtocolMessageType.EXECUTE, sql);
      if (status.isOk()) {
        status = response.status();
      }
      return status.isOk() ? copyCommand(result) : status;
    }

    @Override
    public StatusCode beginQuery(String sql, QueryOpenResult result) {
      if (result == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (!active) {
        return StatusCode.CLOSED;
      }
      if (query.active) {
        return StatusCode.CONFLICT;
      }
      StatusCode status = exchange(ProtocolMessageType.BEGIN_QUERY, sql);
      if (status.isOk()) {
        status = response.status();
      }
      if (status.isOk()) {
        query.active = true;
        query.rowsReturned = 0;
        query.columnCount = response.columnCount();
        status = result.complete(query);
      }
      return status;
    }

    @Override
    public StatusCode close() {
      if (!active) {
        return StatusCode.CLOSED;
      }
      StatusCode status = exchange(ProtocolMessageType.CLOSE_SESSION, null);
      if (status.isOk()) {
        status = response.status();
      }
      if (status.isOk()) {
        active = false;
        query.active = false;
        sessionClosed();
      }
      return status;
    }

    private void resetForOpen() {
      active = true;
      query.active = false;
      query.rowsReturned = 0;
      query.columnCount = 0;
    }

    private final class RemoteQuery implements RiverQuery {
      private long rowsReturned;
      private int columnCount;
      private boolean active;

      @Override
      public StatusCode next(RowResult result) {
        if (result == null) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        result.reset();
        if (!RemoteSession.this.active || !active) {
          return StatusCode.CLOSED;
        }
        StatusCode status = exchange(ProtocolMessageType.FETCH, null);
        if (status.isOk()) {
          status = response.status();
        }
        if (!status.isOk()) {
          return status;
        }
        rowsReturned = response.rowsReturned();
        if (!response.rowAvailable()) {
          return StatusCode.OK;
        }
        int columns = response.columnCount();
        for (int index = 0; index < columns; index++) {
          values[index] = response.valueAt(index);
        }
        return result.complete(response.key(), values, columns);
      }

      @Override
      public StatusCode close(CommandResult result) {
        if (result == null) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        result.reset();
        if (!RemoteSession.this.active || !active) {
          return StatusCode.CLOSED;
        }
        StatusCode status = exchange(ProtocolMessageType.CLOSE_QUERY, null);
        if (status.isOk()) {
          status = response.status();
        }
        if (status.isOk()) {
          active = false;
          columnCount = 0;
          status = copyCommand(result);
        }
        return status;
      }

      @Override
      public boolean isActive() {
        return active;
      }

      @Override
      public int columnCount() {
        return columnCount;
      }

      @Override
      public long rowsReturned() {
        return rowsReturned;
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

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // The connection never escaped; no more useful status can be returned.
    }
  }
}
