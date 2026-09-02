package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryMetadata;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
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
  private final RiverClientResultWorkspace results = new RiverClientResultWorkspace();
  private final RemoteSession session = new RemoteSession();
  private final RiverClientRemotePrograms programs;
  private final Socket socket;
  final InputStream input;
  final OutputStream output;
  volatile StatusCode lastStatus = StatusCode.OK;
  long nextRequestId = 1;
  long completedRequests;
  long bytesSent;
  long bytesReceived;
  boolean responseFullyRead;
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
    programs = new RiverClientRemotePrograms(this);
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

  @Override
  public synchronized StatusCode deferTerminalClose(RiverSession unreachable) {
    if (unreachable != session || !sessionActive) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = closeSocket();
    if (status.isOk() || status == StatusCode.CLOSED) {
      sessionActive = false;
      session.active = false;
      session.query.clearQueryState();
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

  private synchronized StatusCode exchange(
      ProtocolMessageType type,
      String text,
      ParameterSet parameters,
      byte[] payload,
      int payloadBytes) {
    return RiverClientExchange.exchange(
        this, type, text, parameters, payload, payloadBytes, 0);
  }

  private synchronized StatusCode exchangePrepared(
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

  private void sessionClosed() {
    sessionActive = false;
  }

  private StatusCode copyCommand(CommandResult target) {
    return results.copyCommand(response, target);
  }

  private final class RemoteSession implements RiverSession {
    private final RemoteQuery query = new RemoteQuery();
    private boolean active;

    @Override
    public StatusCode prepare(String sql, PreparedOpenResult result) {
      if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      result.reset();
      if (!active) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      StatusCode status = exchange(ProtocolMessageType.PREPARE, sql, null, null, 0);
      if (status.isOk()) status = response.status();
      return status.isOk()
          ? result.complete(response.key(), response.affectedRows(),
              (response.flags() & ProtocolFrameCodec.FLAG_PREPARED_QUERY) != 0)
          : status;
    }

    @Override
    public StatusCode executePrepared(
        long handle, ParameterSet parameters, CommandResult result) {
      if (parameters == null || result == null || handle <= 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (!active) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      StatusCode status = exchangePrepared(
          ProtocolMessageType.EXECUTE_PREPARED, handle, parameters);
      if (status.isOk()) status = response.status();
      return status.isOk() ? copyCommand(result) : status;
    }

    @Override
    public StatusCode beginPreparedQuery(
        long handle, ParameterSet parameters, QueryOpenResult result) {
      if (parameters == null || result == null || handle <= 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (!active) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      StatusCode status = exchangePrepared(
          ProtocolMessageType.BEGIN_PREPARED_QUERY, handle, parameters);
      return status.isOk() ? finishQueryOpen(response.status(), result) : status;
    }

    @Override
    public StatusCode closePrepared(long handle) {
      if (handle <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (!active) return StatusCode.CLOSED;
      if (query.active) return StatusCode.CONFLICT;
      StatusCode status = exchangePrepared(
          ProtocolMessageType.CLOSE_PREPARED, handle, null);
      return status.isOk() ? response.status() : status;
    }

    @Override
    public StatusCode prepareProgram(
        TransactionProgram program, ProgramOpenResult result) {
      return programs.prepare(program, result, active, query.active);
    }

    @Override
    public StatusCode executeProgram(
        long programHandle, IsolationLevel isolationLevel,
        TransactionProgramArguments arguments,
        TransactionProgramResult result) {
      return programs.execute(
          programHandle, isolationLevel, arguments, result, active, query.active);
    }

    @Override
    public StatusCode closeProgram(long programHandle) {
      return programs.close(programHandle, active, query.active);
    }

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
      return status.isOk() ? finishQueryOpen(response.status(), result) : status;
    }

    private StatusCode finishQueryOpen(StatusCode status, QueryOpenResult result) {
      query.serverActive = response.queryActive();
      boolean opened = status.isOk();
      if (opened) status = query.prepareMetadata();
      if (status.isOk()) {
        query.active = true;
        query.rowsReturned = 0;
        query.prefetched = response.rowAvailable();
        if (response.endOfStream()) query.captureCompletion();
        status = result.complete(query);
      }
      return !status.isOk() && (opened || query.serverActive)
          ? query.cleanupFailedOpen(status) : status;
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
        query.clearQueryState();
        sessionClosed();
      }
      return status;
    }

    private void resetForOpen() {
      active = true;
      query.clearQueryState();
    }

    private final class RemoteQuery implements RiverQuery, QueryMetadata {
      private String[] columnNames = new String[8];
      private int[] typeDescriptors = new int[8];
      private long[] nullableWords = new long[1];
      private long rowsReturned;
      private long generation;
      private int maximumTextBytes;
      private int columnCount;
      private boolean active;
      private boolean serverActive;
      private boolean prefetched;
      private int completionRows;
      private long completionSequence;
      private boolean completionTransactionActive;

      @Override
      public StatusCode next(RowResult result) {
        if (result == null) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        result.reset();
        if (!RemoteSession.this.active || !active) {
          return StatusCode.CLOSED;
        }
        StatusCode status = results.reserve(columnCount);
        if (status.isOk()) status = result.reserve(this, null);
        if (!status.isOk()) return status;
        if (prefetched) {
          prefetched = false;
          return copyStagedRow(result);
        }
        if (!serverActive) return StatusCode.OK;
        status = exchange(ProtocolMessageType.FETCH, null);
        if (status.isOk()) {
          status = response.status();
          serverActive = response.queryActive();
          if (response.endOfStream()) captureCompletion();
        }
        if (!status.isOk()) {
          return status;
        }
        if (!response.rowAvailable()) return RiverClientConnection.this.fail(StatusCode.CORRUPTION);
        return copyStagedRow(result);
      }

      private StatusCode copyStagedRow(RowResult result) {
        if (rowsReturned == Long.MAX_VALUE
            || response.rowsReturned() != rowsReturned + 1) {
          return RiverClientConnection.this.fail(StatusCode.CORRUPTION);
        }
        int columns = response.columnCount();
        if (!matchesQueryShape(columns)) {
          return RiverClientConnection.this.fail(StatusCode.CORRUPTION);
        }
        StatusCode status = results.copyRow(response, result, typeDescriptors, columns);
        if (status.isOk()) rowsReturned = response.rowsReturned();
        return status;
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
        StatusCode status = StatusCode.OK;
        if (serverActive) {
          status = exchange(ProtocolMessageType.CLOSE_QUERY, null);
          if (status.isOk()) status = response.status();
          if (status.isOk() && (response.queryActive() || response.rowAvailable())) {
            status = RiverClientConnection.this.fail(StatusCode.CORRUPTION);
          }
          if (status.isOk()) captureCompletion();
        }
        status = serverActive || !status.isOk() ? status : completeLocally(result);
        if (status.isOk()) {
          clearQueryState();
        }
        return status;
      }

      @Override
      public boolean isActive() {
        return active;
      }

      @Override
      public QueryMetadata metadata() {
        return this;
      }

      @Override
      public int maximumEncodedTextBytes() {
        return maximumTextBytes;
      }

      @Override
      public long reservationGeneration() {
        return generation;
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
            && (nullableWords[index >>> 6] & 1L << (index & 63)) != 0;
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
        for (int index = 0; index < columnCount; index++) {
          typeDescriptors[index] = 0;
        }
      }

      private StatusCode prepareMetadata() {
        int columns = response.columnCount();
        if (columns < 0 || columns > SqlShapeLimits.MAX_RESULT_COLUMNS) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        if (generation == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
        long textBytes = 0;
        for (int index = 0; index < columns; index++) {
          int descriptor = response.typeDescriptorAt(index);
          if (!SqlTypeDescriptor.isValid(descriptor)) return StatusCode.CORRUPTION;
          if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
            textBytes += (long) SqlTypeDescriptor.parameterOne(descriptor) * 4;
            if (textBytes > SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES) {
              return StatusCode.RESOURCE_EXHAUSTED;
            }
          }
        }
        StatusCode status = reserveMetadata(columns);
        if (!status.isOk()) return status;
        Arrays.fill(nullableWords, 0L);
        for (int index = 0; index < columns; index++) {
          if (response.columnIsNullable(index)) {
            nullableWords[index >>> 6] |= 1L << (index & 63);
          }
        }
        for (int index = 0; index < columns; index++) {
          columnNames[index] = response.columnName(index);
          typeDescriptors[index] = response.typeDescriptorAt(index);
        }
        columnCount = columns;
        maximumTextBytes = (int) textBytes;
        generation++;
        return StatusCode.OK;
      }

      private StatusCode reserveMetadata(int columns) {
        if (columns <= columnNames.length) return StatusCode.OK;
        int capacity = Math.min(
            SqlShapeLimits.MAX_RESULT_COLUMNS, Math.max(columns, columnNames.length << 1));
        try {
          String[] names = Arrays.copyOf(columnNames, capacity);
          int[] descriptors = Arrays.copyOf(typeDescriptors, capacity);
          long[] nullable = Arrays.copyOf(nullableWords, (capacity + 63) >>> 6);
          columnNames = names;
          typeDescriptors = descriptors;
          nullableWords = nullable;
          return StatusCode.OK;
        } catch (OutOfMemoryError failure) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
      }

      private void clearNullable() {
        Arrays.fill(nullableWords, 0L);
      }

      private StatusCode cleanupFailedOpen(StatusCode failure) {
        StatusCode cleanup = StatusCode.OK;
        if (serverActive) {
          cleanup = exchange(ProtocolMessageType.CLOSE_QUERY, null);
          if (cleanup.isOk()) cleanup = response.status();
        }
        clearQueryState();
        return cleanup.isOk() ? failure : RiverClientConnection.this.fail(cleanup);
      }

      private void captureCompletion() {
        serverActive = false;
        completionRows = response.affectedRows();
        completionSequence = response.commitSequence();
        completionTransactionActive = response.transactionActive();
      }

      private StatusCode completeLocally(CommandResult result) {
        return result.complete(
            completionRows, completionSequence, completionTransactionActive,
            false, 0, null, 0, null, 0);
      }

      private void clearQueryState() {
        active = false;
        serverActive = false;
        prefetched = false;
        rowsReturned = 0;
        completionRows = 0;
        completionSequence = 0;
        completionTransactionActive = false;
        clearColumnNames();
        clearTypeDescriptors();
        clearNullable();
        columnCount = 0;
        maximumTextBytes = 0;
      }
    }
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
}
