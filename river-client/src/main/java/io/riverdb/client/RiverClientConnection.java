package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
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
  final ByteBuffer request =
      ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
  final byte[] responseBytes = new byte[ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES];
  final ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
  private final long[] values = new long[CommandResult.MAXIMUM_COLUMNS];
  private final int[] typeDescriptors = new int[CommandResult.MAXIMUM_COLUMNS];
  private final char[] textCharacters =
      new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final RemoteSession session = new RemoteSession();
  private final Socket socket;
  final InputStream input;
  final OutputStream output;
  volatile StatusCode lastStatus = StatusCode.OK;
  long nextRequestId = 1;
  long completedRequests;
  long bytesSent;
  long bytesReceived;
  private boolean sessionActive;
  volatile boolean cancelled;
  volatile boolean closed;

  RiverClientConnection(
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

  private synchronized StatusCode exchange(
      ProtocolMessageType type,
      String text,
      ParameterSet parameters,
      byte[] payload,
      int payloadBytes) {
    return RiverClientExchange.exchange(
        this, type, text, parameters, payload, payloadBytes);
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

  private void sessionClosed() {
    sessionActive = false;
  }

  private StatusCode copyCommand(CommandResult target) {
    int columns = response.columnCount();
    for (int index = 0; index < columns; index++) {
      values[index] = response.valueAt(index);
      typeDescriptors[index] = response.typeDescriptorAt(index);
    }
    StatusCode status = target.complete(
        response.affectedRows(),
        response.commitSequence(),
        response.transactionActive(),
        response.rowAvailable(),
        response.key(),
        values,
        response.nullMask(),
        typeDescriptors,
        columns);
    for (int index = 0; status.isOk() && index < columns; index++) {
      if (response.isVarchar(index) && !response.isNull(index)) {
        int length = response.copyTextAt(index, textCharacters, 0);
        status = length < 0
            ? StatusCode.INVALID_EXTERNAL_INPUT
            : target.setTextAt(index, textCharacters, 0, length);
      }
    }
    return status;
  }

  private final class RemoteSession implements RiverSession {
    private final RemoteQuery query = new RemoteQuery();
    private boolean active;

    @Override
    public StatusCode execute(String sql, CommandResult result) {
      return executeRequest(sql, null, result, false);
    }

    @Override
    public StatusCode execute(
        String sql, ParameterSet parameters, CommandResult result) {
      return executeRequest(sql, parameters, result, true);
    }

    private StatusCode executeRequest(
        String sql,
        ParameterSet parameters,
        CommandResult result,
        boolean typed) {
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
      if (typed && parameters == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = exchange(
          ProtocolMessageType.EXECUTE, sql, parameters, null, 0);
      if (status.isOk()) {
        status = response.status();
      }
      return status.isOk() ? copyCommand(result) : status;
    }

    @Override
    public StatusCode beginQuery(String sql, QueryOpenResult result) {
      return beginQueryRequest(sql, null, result, false);
    }

    @Override
    public StatusCode beginQuery(
        String sql, ParameterSet parameters, QueryOpenResult result) {
      return beginQueryRequest(sql, parameters, result, true);
    }

    private StatusCode beginQueryRequest(
        String sql,
        ParameterSet parameters,
        QueryOpenResult result,
        boolean typed) {
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
      if (typed && parameters == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = exchange(
          ProtocolMessageType.BEGIN_QUERY, sql, parameters, null, 0);
      if (status.isOk()) {
        status = response.status();
      }
      if (status.isOk()) {
        query.active = true;
        query.rowsReturned = 0;
        query.columnCount = response.columnCount();
        query.nullableMask = response.nullMask();
        for (int index = 0; index < query.columnCount; index++) {
          query.columnNames[index] = response.columnName(index);
          query.typeDescriptors[index] = response.typeDescriptorAt(index);
        }
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
        query.clearColumnNames();
        query.columnCount = 0;
        query.nullableMask = 0;
        query.clearTypeDescriptors();
        sessionClosed();
      }
      return status;
    }

    private void resetForOpen() {
      active = true;
      query.active = false;
      query.rowsReturned = 0;
      query.clearColumnNames();
      query.columnCount = 0;
      query.nullableMask = 0;
      query.clearTypeDescriptors();
    }

    private final class RemoteQuery implements RiverQuery {
      private final String[] columnNames = new String[CommandResult.MAXIMUM_COLUMNS];
      private final int[] typeDescriptors = new int[CommandResult.MAXIMUM_COLUMNS];
      private long rowsReturned;
      private long nullableMask;
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
        if (!matchesQueryShape(columns)) {
          return RiverClientConnection.this.fail(StatusCode.CORRUPTION);
        }
        for (int index = 0; index < columns; index++) {
          values[index] = response.valueAt(index);
          RiverClientConnection.this.typeDescriptors[index] =
              typeDescriptors[index];
        }
        StatusCode completed = result.complete(
            response.key(),
            values,
            response.nullMask(),
            RiverClientConnection.this.typeDescriptors,
            columns);
        for (int index = 0; completed.isOk() && index < columns; index++) {
          if (response.isVarchar(index) && !response.isNull(index)) {
            int length = response.copyTextAt(index, textCharacters, 0);
            completed = length < 0
                ? StatusCode.INVALID_EXTERNAL_INPUT
                : result.setTextAt(index, textCharacters, 0, length);
          }
        }
        return completed;
      }

      private boolean matchesQueryShape(int columns) {
        if (columns != columnCount) return false;
        for (int index = 0; index < columns; index++) {
          if (response.typeDescriptorAt(index) != typeDescriptors[index]) {
            return false;
          }
        }
        return true;
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
          clearColumnNames();
          clearTypeDescriptors();
          columnCount = 0;
          nullableMask = 0;
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
      public CharSequence columnName(int index) {
        return index >= 0 && index < columnCount ? columnNames[index] : null;
      }

      @Override
      public int columnTypeDescriptor(int index) {
        return index >= 0 && index < columnCount ? typeDescriptors[index] : 0;
      }

      @Override
      public boolean columnIsNullable(int index) {
        return index >= 0 && index < columnCount
            && (nullableMask & 1L << index) != 0;
      }

      @Override
      public long rowsReturned() {
        return rowsReturned;
      }

      private void clearColumnNames() {
        for (int index = 0; index < columnNames.length; index++) {
          columnNames[index] = null;
        }
      }

      private void clearTypeDescriptors() {
        for (int index = 0; index < typeDescriptors.length; index++) {
          typeDescriptors[index] = 0;
        }
      }
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
}
