package io.riverdb.server;

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
import io.riverdb.protocol.ProtocolTextDecoder;
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
  private final long challengeHigh;
  private final long challengeLow;
  private final byte[] channelBinding;
  private final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  private final ProtocolFrame frame = new ProtocolFrame();
  private final ProtocolTextDecoder text =
      new ProtocolTextDecoder(ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES);
  private final SessionOpenResult openedSession = new SessionOpenResult();
  private final QueryOpenResult openedQuery = new QueryOpenResult();
  private final CommandResult command = new CommandResult();
  private final RowResult row = new RowResult();
  private RiverSession session;
  private RiverQuery query;
  private int authenticationAttempts;
  private int state;

  public SessionEndpoint(RiverDatabase engineDatabase) {
    this(engineDatabase, null, 0, 0, null);
  }

  SessionEndpoint(
      RiverDatabase engineDatabase,
      TokenAuthenticator tokenAuthenticator,
      long nonceHigh,
      long nonceLow,
      byte[] binding) {
    database = engineDatabase;
    authenticator = tokenAuthenticator;
    challengeHigh = nonceHigh;
    challengeLow = nonceLow;
    channelBinding = binding;
  }

  /**
   * Processes exactly one complete frame. OK means a response was encoded; the
   * operation status is carried inside that response.
   */
  public StatusCode process(ByteBuffer request, ByteBuffer response) {
    if (request == null || response == null || request == response || response.isReadOnly()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (response.capacity() < ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES) {
      empty(response);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    empty(response);
    StatusCode decoded = codec.decode(request, frame);
    if (!decoded.isOk()) {
      return decoded;
    }
    if (frame.isResponse()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    ProtocolMessageType type = frame.type();
    if (type.requiresPayload() != (frame.payloadBytes() > 0)) {
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
    };
  }

  public StatusCode close() {
    if (state == CLOSED) {
      return StatusCode.CLOSED;
    }
    StatusCode status = StatusCode.OK;
    if (session != null) {
      status = session.close();
    }
    if (status.isOk() || status == StatusCode.CLOSED) {
      session = null;
      query = null;
      state = CLOSED;
      clearChannelBinding();
      return StatusCode.OK;
    }
    return status;
  }

  public boolean isClosed() {
    return state == CLOSED;
  }

  int authenticationFailures() {
    return authenticationAttempts;
  }

  boolean authenticationComplete() {
    return authenticator == null || state >= READY && state < CLOSED;
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
      if (status.isOk()) {
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
        ? database.createSession(openedSession)
        : state == CLOSED ? StatusCode.CLOSED : StatusCode.CONFLICT;
    if (status.isOk()) {
      session = openedSession.session();
      state = SESSION;
    }
    return codec.encodeStatusResponse(response, frame.type(), frame.requestId(), status, false);
  }

  private StatusCode execute(ByteBuffer response) {
    command.reset();
    StatusCode status = requireTextInSession();
    if (status.isOk()) {
      status = session.execute(text.text(), command);
    }
    return codec.encodeCommandResponse(
        response, frame.type(), frame.requestId(), status, command, false);
  }

  private StatusCode beginQuery(ByteBuffer response) {
    StatusCode status = requireTextInSession();
    if (status.isOk()) {
      openedQuery.reset();
      status = session.beginQuery(text.text(), openedQuery);
    }
    if (status.isOk()) {
      query = openedQuery.query();
      state = QUERY;
    }
    return codec.encodeStatusResponse(
        response, frame.type(), frame.requestId(), status, state == QUERY);
  }

  private StatusCode fetch(ByteBuffer response) {
    row.reset();
    StatusCode status = state == QUERY
        ? query.next(row)
        : state == CLOSED ? StatusCode.CLOSED : StatusCode.CONFLICT;
    return codec.encodeRowResponse(
        response,
        frame.type(),
        frame.requestId(),
        status,
        row,
        query == null ? 0 : query.rowsReturned(),
        state == QUERY);
  }

  private StatusCode closeQuery(ByteBuffer response) {
    command.reset();
    StatusCode status = state == QUERY
        ? query.close(command)
        : state == CLOSED ? StatusCode.CLOSED : StatusCode.CONFLICT;
    if (status.isOk()) {
      query = null;
      state = SESSION;
    }
    return codec.encodeCommandResponse(
        response, frame.type(), frame.requestId(), status, command, state == QUERY);
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
      state = READY;
    }
    return codec.encodeStatusResponse(response, frame.type(), frame.requestId(), status, false);
  }

  private StatusCode requireTextInSession() {
    if (state == CLOSED) {
      return StatusCode.CLOSED;
    }
    if (state != SESSION) {
      return StatusCode.CONFLICT;
    }
    return text.decode(frame);
  }

  private static void empty(ByteBuffer response) {
    if (response != null) {
      response.clear();
      response.limit(0);
    }
  }
}
