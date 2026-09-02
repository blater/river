package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.protocol.ProtocolFrame;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import io.riverdb.protocol.ProtocolPreparedRequestDecoder;
import io.riverdb.protocol.ProtocolProgramRequestDecoder;
import io.riverdb.protocol.ProtocolQueryMetadata;
import io.riverdb.protocol.ProtocolSqlRequestDecoder;
import io.riverdb.protocol.ProtocolWorkspaceRetention;
import io.riverdb.protocol.auth.TokenAuthenticator;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** One ordered protocol connection bound to at most one engine session and query. */
public final class SessionEndpoint {
  private static final int NEW = 0;
  private static final int AUTHENTICATING = 1;
  private static final int READY = 2;
  private static final int SESSION = 3;
  private static final int QUERY = 4;
  private static final int CLOSED = 5;
  private static final int MAXIMUM_AUTHENTICATION_ATTEMPTS = 3;

  private final RiverDatabase database;
  private final TokenAuthenticator authenticator;
  private final RemoteSessionAuthorizer sessionAuthorizer;
  private final long challengeHigh;
  private final long challengeLow;
  private final byte[] channelBinding;
  private final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  private final ProtocolFrame frame = new ProtocolFrame();
  private final SessionOpenResult openedSession = new SessionOpenResult();
  private final QueryOpenResult openedQuery = new QueryOpenResult();
  private final CommandResult command;
  private final PreparedOpenResult openedPrepared = new PreparedOpenResult();
  private final ProgramOpenResult openedProgram = new ProgramOpenResult();
  private final ProtocolPreparedRequestDecoder preparedRequest;
  private final ProtocolProgramRequestDecoder programRequest;
  private final TransactionProgramResult programResult;
  private final ServerResponseBuffer programResponses;
  private final ProtocolQueryMetadata queryMetadata = new ProtocolQueryMetadata();
  private RowResult row;
  private RowResult lookahead;
  private final ServerConnectionMemory memory;
  private RiverSession session;
  private RiverQuery query;
  private ProtocolSqlRequestDecoder sqlRequest;
  private int authenticationAttempts;
  private int state;
  private int pendingResponse;
  private StatusCode pendingStatus;
  private ProtocolMessageType pendingType;
  private long pendingRequestId;
  private boolean pendingQueryActive;
  private long rowsReturned;

  public SessionEndpoint(RiverDatabase engineDatabase) {
    this(engineDatabase, null, 0, 0, null, null, null);
  }

  SessionEndpoint(
      RiverDatabase engineDatabase,
      TokenAuthenticator tokenAuthenticator,
      long nonceHigh,
      long nonceLow,
      byte[] binding) {
    this(engineDatabase, tokenAuthenticator, nonceHigh, nonceLow, binding, null, null);
  }

  SessionEndpoint(
      RiverDatabase engineDatabase,
      TokenAuthenticator tokenAuthenticator,
      long nonceHigh,
      long nonceLow,
      byte[] binding,
      SecurityAuditLog audit) {
    this(engineDatabase, tokenAuthenticator, nonceHigh, nonceLow, binding, audit, null);
  }

  SessionEndpoint(
      RiverDatabase engineDatabase,
      TokenAuthenticator tokenAuthenticator,
      long nonceHigh,
      long nonceLow,
      byte[] binding,
      SecurityAuditLog audit,
      ServerConnectionMemory connectionMemory) {
    this(engineDatabase, tokenAuthenticator, nonceHigh, nonceLow, binding,
        audit, connectionMemory, null);
  }

  SessionEndpoint(
      RiverDatabase engineDatabase,
      TokenAuthenticator tokenAuthenticator,
      long nonceHigh,
      long nonceLow,
      byte[] binding,
      SecurityAuditLog audit,
      ServerConnectionMemory connectionMemory,
      ServerResponseBuffer responseProvider) {
    database = engineDatabase;
    memory = connectionMemory;
    command = new CommandResult(lease());
    preparedRequest = new ProtocolPreparedRequestDecoder(lease());
    programRequest = new ProtocolProgramRequestDecoder(lease());
    programResponses = responseProvider;
    programResult = new TransactionProgramResult(lease(), responseProvider);
    row = new RowResult(lease());
    lookahead = new RowResult(lease());
    authenticator = tokenAuthenticator;
    sessionAuthorizer = tokenAuthenticator == null
        ? null
        : new RemoteSessionAuthorizer(
            tokenAuthenticator.principalId(),
            tokenAuthenticator.permissions(),
            audit);
    challengeHigh = nonceHigh;
    challengeLow = nonceLow;
    channelBinding = binding;
  }

  /**
   * Processes exactly one complete frame. OK means a response was encoded; the
   * operation status is carried inside that response.
   */
  public StatusCode process(ByteBuffer request, ByteBuffer response) {
    pendingResponse = 0;
    StatusCode decoded = ProtocolRequestAdmission.validate(codec, request, response);
    if (!decoded.isOk()) return decoded;
    decoded = ProtocolRequestAdmission.decode(codec, request, frame);
    if (!decoded.isOk()) {
      return decoded;
    }
    if (frame.isResponse()) {
      frame.erasePayload();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    ProtocolMessageType type = frame.type();
    if (type.requiresPayload() != (frame.payloadBytes() > 0)) {
      StatusCode erased = frame.erasePayload();
      if (!erased.isOk()) {
        return erased;
      }
      return codec.encodeStatusResponse(
          response, type, frame.requestId(), StatusCode.INVALID_EXTERNAL_INPUT, state == QUERY);
    }
    return switch (type) {
      case HELLO -> hello(response);
      case AUTHENTICATE -> authenticate(response);
      case OPEN_SESSION -> openSession(response);
      case EXECUTE -> execute(response);
      case BEGIN_QUERY -> beginQuery(response);
      case FETCH -> fetch(response);
      case CLOSE_QUERY -> closeQuery(response);
      case CLOSE_SESSION -> closeSession(response);
      case PREPARE -> prepare(response);
      case EXECUTE_PREPARED -> executePrepared(response);
      case BEGIN_PREPARED_QUERY -> beginPreparedQuery(response);
      case CLOSE_PREPARED -> closePrepared(response);
      case PREPARE_PROGRAM -> prepareProgram(response);
      case EXECUTE_PROGRAM -> executeProgram(response);
      case CLOSE_PROGRAM -> closeProgram(response);
    };
  }

  StatusCode retryResponse(ByteBuffer response) {
    return switch (pendingResponse) {
      case 1 -> encodeCommand(response, pendingType, pendingRequestId,
          pendingStatus, pendingQueryActive);
      case 2 -> encodeQuery(response, pendingType, pendingRequestId, pendingStatus);
      case 3 -> encodeRow(response, pendingType, pendingRequestId,
          pendingStatus, pendingQueryActive);
      case 4 -> encodePrepared(response, pendingRequestId, pendingStatus);
      case 5 -> encodeProgramOpen(response, pendingRequestId, pendingStatus);
      case 6 -> encodeProgramResult(response, pendingRequestId, pendingStatus);
      default -> StatusCode.INVARIANT_BROKEN;
    };
  }

  public StatusCode close() {
    if (state == CLOSED) {
      return StatusCode.CLOSED;
    }
    StatusCode status = StatusCode.OK;
    boolean released = session == null;
    if (session != null) {
      status = session.close();
      released = status.isOk() || status == StatusCode.CLOSED;
      if (!status.isOk() && status != StatusCode.CLOSED) {
        StatusCode transferred = database.deferTerminalClose(session);
        released = transferred.isOk();
        if (!released) status = transferred;
      }
    }
    if (released) {
      session = null;
      query = null;
      StatusCode memoryStatus = releaseAllMemory();
      sqlRequest = null;
      state = CLOSED;
      clearChannelBinding();
      StatusCode terminal = status == StatusCode.CLOSED ? StatusCode.OK : status;
      return terminal.isOk() ? memoryStatus : terminal;
    }
    return status;
  }

  public boolean isClosed() {
    return state == CLOSED;
  }

  void releasePublishedHighWater() {
    command.releaseHighWater();
    preparedRequest.releaseHighWater();
    if (sqlRequest != null) sqlRequest.releaseHighWater();
    if (state != QUERY) {
      row.releaseHighWater();
      lookahead.releaseHighWater();
    }
    if (ProtocolWorkspaceRetention.shouldShed(programRequest.retainedBytes())) {
      programRequest.releaseHighWater();
    }
    long programResultBytes = programResult.retainedBytes();
    programResult.reset();
    if (ProtocolWorkspaceRetention.shouldShed(programResultBytes)) {
      programResult.release();
    }
  }

  private StatusCode releaseAllMemory() {
    StatusCode status = command.release();
    status = firstFailure(status, preparedRequest.release());
    status = firstFailure(status, programRequest.release());
    status = firstFailure(status, programResult.release());
    if (sqlRequest != null) status = firstFailure(status, sqlRequest.release());
    status = firstFailure(status, row.release());
    return firstFailure(status, lookahead.release());
  }

  private static StatusCode firstFailure(StatusCode current, StatusCode next) {
    return current.isOk() ? next : current;
  }

  private RetainedMemoryLease lease() {
    return memory == null ? RetainedMemoryLease.unbounded() : memory.lease();
  }

  int authenticationFailures() {
    return authenticationAttempts;
  }

  boolean authenticationComplete() {
    return authenticator == null || state >= READY && state < CLOSED;
  }

  long authorizationFailures() {
    return sessionAuthorizer == null ? 0 : sessionAuthorizer.denials();
  }

  private StatusCode hello(ByteBuffer response) {
    StatusCode status = state == NEW ? StatusCode.OK : StatusCode.CONFLICT;
    if (status.isOk()) {
      state = authenticator == null ? READY : AUTHENTICATING;
    }
    return codec.encodeHelloResponse(
        response,
        frame.requestId(),
        status,
        authenticator == null ? 0 : challengeHigh,
        authenticator == null ? 0 : challengeLow);
  }

  private StatusCode authenticate(ByteBuffer response) {
    StatusCode status;
    if (state == AUTHENTICATING) {
      status = authenticator.verify(frame, challengeHigh, challengeLow, channelBinding);
      StatusCode audited = sessionAuthorizer.auditAuthentication(status.isOk());
      if (!audited.isOk()) {
        state = CLOSED;
        status = audited;
        clearChannelBinding();
      } else if (status.isOk()) {
        state = READY;
        clearChannelBinding();
      } else {
        authenticationAttempts++;
        if (authenticationAttempts >= MAXIMUM_AUTHENTICATION_ATTEMPTS) {
          state = CLOSED;
          status = StatusCode.FENCED;
          clearChannelBinding();
        }
      }
    } else {
      frame.erasePayload();
      status = state == CLOSED ? StatusCode.CLOSED : StatusCode.CONFLICT;
    }
    return codec.encodeStatusResponse(response, frame.type(), frame.requestId(), status, false);
  }

  private void clearChannelBinding() {
    if (channelBinding != null) {
      Arrays.fill(channelBinding, (byte) 0);
    }
  }

  private StatusCode openSession(ByteBuffer response) {
    openedSession.reset();
    StatusCode status = state == READY
        ? StatusCode.OK : state == CLOSED ? StatusCode.CLOSED : StatusCode.CONFLICT;
    ProtocolSqlRequestDecoder decoder = null;
    if (status.isOk()) {
      try {
        decoder = new ProtocolSqlRequestDecoder(lease(), lease());
      } catch (OutOfMemoryError failure) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    if (status.isOk()) {
      status = sessionAuthorizer == null
          ? database.createSession(openedSession)
          : database.createSession(sessionAuthorizer, openedSession);
    }
    if (status.isOk()) {
      session = openedSession.session();
      sqlRequest = decoder;
      state = SESSION;
    }
    return codec.encodeStatusResponse(response, frame.type(), frame.requestId(), status, false);
  }

  private StatusCode execute(ByteBuffer response) {
    command.reset();
    StatusCode status = requireSqlInSession();
    try {
      if (status.isOk()) {
        status = session.execute(
            sqlRequest.sql(), sqlRequest.parameters(), command);
      }
    } finally {
      releaseSqlRequest();
    }
    return encodeCommand(response, frame.type(), frame.requestId(), status, false);
  }

  private StatusCode prepare(ByteBuffer response) {
    openedPrepared.reset();
    StatusCode status = requireSqlInSession();
    try {
      if (status.isOk()) status = session.prepare(sqlRequest.sql(), openedPrepared);
    } finally {
      releaseSqlRequest();
    }
    return encodePrepared(response, frame.requestId(), status);
  }

  private StatusCode executePrepared(ByteBuffer response) {
    command.reset();
    StatusCode status = requirePreparedInSession();
    try {
      if (status.isOk()) {
        status = session.executePrepared(
            preparedRequest.handle(), preparedRequest.parameters(), command);
      }
    } finally {
      preparedRequest.reset();
    }
    return encodeCommand(response, frame.type(), frame.requestId(), status, false);
  }

  private StatusCode beginPreparedQuery(ByteBuffer response) {
    StatusCode status = requirePreparedInSession();
    try {
      if (status.isOk()) {
        openedQuery.reset();
        status = session.beginPreparedQuery(
            preparedRequest.handle(), preparedRequest.parameters(), openedQuery);
      }
    } finally {
      preparedRequest.reset();
    }
    if (status.isOk()) {
      query = openedQuery.query();
      state = QUERY;
      status = stageQueryOpen();
    }
    return encodeQuery(response, frame.type(), frame.requestId(), status);
  }

  private StatusCode closePrepared(ByteBuffer response) {
    StatusCode status = requirePreparedInSession();
    try {
      if (status.isOk()) status = session.closePrepared(preparedRequest.handle());
    } finally {
      preparedRequest.reset();
    }
    return codec.encodeStatusResponse(response, frame.type(), frame.requestId(), status, false);
  }

  private StatusCode prepareProgram(ByteBuffer response) {
    openedProgram.reset();
    StatusCode status = requireProgramInSession();
    try {
      if (status.isOk()) {
        status = session.prepareProgram(programRequest.program(), openedProgram);
      }
    } finally {
      programRequest.reset();
    }
    return encodeProgramOpen(response, frame.requestId(), status);
  }

  private StatusCode executeProgram(ByteBuffer response) {
    programResult.reset();
    StatusCode status = requireProgramInSession();
    try {
      if (status.isOk()) {
        status = session.executeProgram(
            programRequest.handle(), programRequest.isolationLevel(),
            programRequest.arguments(), programResult);
      }
    } finally {
      programRequest.reset();
    }
    return encodeProgramResult(response, frame.requestId(), status);
  }

  private StatusCode closeProgram(ByteBuffer response) {
    StatusCode status = requireProgramInSession();
    try {
      if (status.isOk()) status = session.closeProgram(programRequest.handle());
    } finally {
      programRequest.reset();
    }
    return codec.encodeStatusResponse(
        response, frame.type(), frame.requestId(), status, false);
  }

  private StatusCode beginQuery(ByteBuffer response) {
    StatusCode status = requireSqlInSession();
    try {
      if (status.isOk()) {
        openedQuery.reset();
        status = session.beginQuery(
            sqlRequest.sql(), sqlRequest.parameters(), openedQuery);
      }
    } finally {
      releaseSqlRequest();
    }
    if (status.isOk()) {
      query = openedQuery.query();
      state = QUERY;
      status = stageQueryOpen();
    }
    return encodeQuery(response, frame.type(), frame.requestId(), status);
  }

  private StatusCode fetch(ByteBuffer response) {
    if (state != QUERY) {
      row.reset();
      command.reset();
      StatusCode status = state == CLOSED ? StatusCode.CLOSED : StatusCode.CONFLICT;
      return encodeCommand(response, frame.type(), frame.requestId(), status, false);
    }
    StatusCode status = stageNextRow();
    return encodeRow(response, frame.type(), frame.requestId(), status, state == QUERY);
  }

  private StatusCode closeQuery(ByteBuffer response) {
    command.reset();
    StatusCode status = state == QUERY
        ? completeQuery(StatusCode.OK)
        : state == CLOSED ? StatusCode.CLOSED : StatusCode.CONFLICT;
    return encodeCommand(
        response, frame.type(), frame.requestId(), status, state == QUERY);
  }

  private StatusCode stageQueryOpen() {
    row.reset();
    lookahead.reset();
    rowsReturned = 0;
    StatusCode status = queryMetadata.capture(query);
    if (status.isOk()) status = query.next(row);
    if (!status.isOk()) {
      row.reset();
      return completeQuery(status);
    }
    if (!row.isAvailable()) return completeQuery(StatusCode.OK);
    status = query.next(lookahead);
    if (!status.isOk()) {
      row.reset();
      return completeQuery(status);
    }
    rowsReturned = 1;
    return lookahead.isAvailable() ? StatusCode.OK : completeQuery(StatusCode.OK);
  }

  private StatusCode stageNextRow() {
    RowResult consumed = row;
    row = lookahead;
    lookahead = consumed;
    lookahead.reset();
    rowsReturned++;
    StatusCode status = query.next(lookahead);
    if (!status.isOk()) {
      row.reset();
      return completeQuery(status);
    }
    return lookahead.isAvailable() ? StatusCode.OK : completeQuery(StatusCode.OK);
  }

  /** Completes every reactive or explicit query end through one state transition. */
  private StatusCode completeQuery(StatusCode primary) {
    command.reset();
    StatusCode completion = query == null
        ? StatusCode.INVARIANT_BROKEN : query.close(command);
    boolean closed = query != null && !query.isActive();
    if (closed) {
      query = null;
      lookahead.reset();
      state = SESSION;
    }
    if (completion.isOk() && !closed) completion = StatusCode.INVARIANT_BROKEN;
    return primary.isOk() ? completion : primary;
  }

  private StatusCode closeSession(ByteBuffer response) {
    StatusCode status;
    if (state == SESSION || state == QUERY) {
      status = session.close();
    } else {
      status = state == CLOSED ? StatusCode.CLOSED : StatusCode.CONFLICT;
    }
    if (status.isOk()) {
      session = null;
      query = null;
      releaseAllMemory();
      sqlRequest = null;
      state = READY;
    }
    return codec.encodeStatusResponse(response, frame.type(), frame.requestId(), status, false);
  }

  private StatusCode requireSqlInSession() {
    if (state == CLOSED) {
      frame.erasePayload();
      return StatusCode.CLOSED;
    }
    if (state != SESSION) {
      frame.erasePayload();
      return StatusCode.CONFLICT;
    }
    return sqlRequest == null
        ? StatusCode.INVARIANT_BROKEN : sqlRequest.decode(frame);
  }

  private StatusCode requirePreparedInSession() {
    if (state == CLOSED) {
      frame.erasePayload();
      return StatusCode.CLOSED;
    }
    if (state != SESSION) {
      frame.erasePayload();
      return StatusCode.CONFLICT;
    }
    return preparedRequest.decode(frame);
  }

  private StatusCode requireProgramInSession() {
    if (state == CLOSED) {
      frame.erasePayload();
      return StatusCode.CLOSED;
    }
    if (state != SESSION) {
      frame.erasePayload();
      return StatusCode.CONFLICT;
    }
    return codec.decodeProgramRequest(frame, programRequest);
  }

  private void releaseSqlRequest() {
    if (sqlRequest != null) {
      sqlRequest.reset();
    }
  }

  private StatusCode encodeCommand(ByteBuffer response, ProtocolMessageType type,
      long requestId, StatusCode status, boolean queryActive) {
    pendingResponse = 1;
    pendingStatus = status;
    pendingType = type;
    pendingRequestId = requestId;
    pendingQueryActive = queryActive;
    StatusCode encoded = codec.encodeCommandResponse(
        response, type, requestId, status, command, queryActive);
    if (encoded != StatusCode.RESOURCE_EXHAUSTED) pendingResponse = 0;
    return encoded;
  }

  private StatusCode encodeQuery(
      ByteBuffer response, ProtocolMessageType type, long requestId, StatusCode status) {
    pendingResponse = 2;
    pendingStatus = status;
    pendingType = type;
    pendingRequestId = requestId;
    StatusCode encoded = codec.encodeQueryOpenResponse(
        response, type, requestId, status,
        status.isOk() ? queryMetadata : null,
        status.isOk() ? row : null,
        status.isOk() ? rowsReturned : 0,
        status.isOk() && state != QUERY ? command : null,
        state == QUERY);
    if (encoded != StatusCode.RESOURCE_EXHAUSTED) pendingResponse = 0;
    return encoded;
  }

  private StatusCode encodeRow(ByteBuffer response, ProtocolMessageType type,
      long requestId, StatusCode status, boolean queryActive) {
    pendingResponse = 3;
    pendingStatus = status;
    pendingType = type;
    pendingRequestId = requestId;
    pendingQueryActive = queryActive;
    StatusCode encoded = codec.encodeRowResponse(response, type, requestId, status, row,
        rowsReturned, queryActive ? null : command, queryActive);
    if (encoded != StatusCode.RESOURCE_EXHAUSTED) pendingResponse = 0;
    return encoded;
  }

  private StatusCode encodePrepared(
      ByteBuffer response, long requestId, StatusCode status) {
    pendingResponse = 4;
    pendingStatus = status;
    pendingRequestId = requestId;
    StatusCode encoded = codec.encodePrepareResponse(
        response, requestId, status, openedPrepared);
    if (encoded != StatusCode.RESOURCE_EXHAUSTED) pendingResponse = 0;
    return encoded;
  }

  private StatusCode encodeProgramOpen(
      ByteBuffer response, long requestId, StatusCode status) {
    pendingResponse = 5;
    pendingStatus = status;
    pendingRequestId = requestId;
    StatusCode encoded = codec.encodeProgramOpenResponse(
        response, requestId, status, openedProgram);
    if (encoded != StatusCode.RESOURCE_EXHAUSTED) pendingResponse = 0;
    return encoded;
  }

  private StatusCode encodeProgramResult(
      ByteBuffer response, long requestId, StatusCode status) {
    pendingResponse = 6;
    pendingStatus = status;
    pendingRequestId = requestId;
    ByteBuffer target = programResponses == null ? response : programResponses.buffer();
    StatusCode encoded = codec.encodeProgramResultResponse(
        target, requestId, status, programResult);
    if (encoded != StatusCode.RESOURCE_EXHAUSTED) pendingResponse = 0;
    return encoded;
  }

}
