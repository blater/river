package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.ByteBuffer;

/** Bounded v4 framing over caller-owned buffers. */
public final class ProtocolFrameCodec {
  public static final int VERSION = 4;
  public static final int HEADER_BYTES = 32;
  public static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1024;
  public static final int MAXIMUM_FRAME_BYTES = HEADER_BYTES + MAXIMUM_PAYLOAD_BYTES;
  public static final int MAXIMUM_COLUMN_NAME_BYTES = 64;
  public static final int MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES =
      SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES
          + SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES + 64;
  public static final int MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES =
      SqlShapeLimits.MAX_SQL_TEXT_BYTES + SqlShapeLimits.MAX_ENCODED_PARAMETER_BYTES;
  public static final int MAXIMUM_RESPONSE_BYTES =
      ProtocolContinuationLimits.maximumWireBytes(MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES);
  public static final int MAXIMUM_REQUEST_BYTES = continuedRequestWireBytes(
      MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES);
  public static final int FLAG_ROW_AVAILABLE = 1;
  public static final int FLAG_TRANSACTION_ACTIVE = 1 << 1;
  public static final int FLAG_QUERY_ACTIVE = 1 << 2;
  public static final int FLAG_COLUMN_METADATA = 1 << 3;
  public static final int FLAG_PREPARED_QUERY = 1 << 4;
  public static final int FLAG_END_OF_STREAM = 1 << 5;

  private final ProtocolResponseEncoder responses = new ProtocolResponseEncoder();
  private final ProtocolResponseDecoder responseDecoder =
      new ProtocolResponseDecoder();
  private final ProtocolContinuedResponseDecoder continuedResponses =
      new ProtocolContinuedResponseDecoder();
  private final ProtocolSqlRequestEncoder sqlRequests =
      new ProtocolSqlRequestEncoder();
  private final ProtocolPreparedRequestEncoder preparedRequests =
      new ProtocolPreparedRequestEncoder();
  private final ProtocolProgramRequestEncoder programRequests =
      new ProtocolProgramRequestEncoder();
  private final ProtocolProgramOpenResponseEncoder programOpenResponses =
      new ProtocolProgramOpenResponseEncoder();
  private final ProtocolProgramOpenResponseDecoder programOpenResponseDecoder =
      new ProtocolProgramOpenResponseDecoder();
  private final ProtocolProgramResultEncoder programResults =
      new ProtocolProgramResultEncoder();
  private final ProtocolProgramResultDecoder programResultDecoder =
      new ProtocolProgramResultDecoder();

  /** Inspects exactly the request metadata needed before reading its payload. */
  public StatusCode inspectRequestHeader(
      ByteBuffer source, ProtocolFrameHeader result) {
    return ProtocolFrameWire.inspect(
        source, result, ProtocolFrameWire.ROLE_REQUEST);
  }

  /** Inspects exactly the response metadata needed before reading its payload. */
  public StatusCode inspectResponseHeader(
      ByteBuffer source, ProtocolFrameHeader result) {
    return ProtocolFrameWire.inspect(
        source, result, ProtocolFrameWire.ROLE_RESPONSE);
  }

  public StatusCode decode(ByteBuffer source, ProtocolFrame result) {
    return ProtocolFrameWire.decode(
        source, result, ProtocolFrameWire.ROLE_REQUEST);
  }

  /** Best-effort erasure after a malformed request could not become a frame. */
  public StatusCode eraseRequestPayload(ByteBuffer source) {
    if (source == null || source.isReadOnly()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = source.position();
    int payload = Math.min(source.limit(), start + HEADER_BYTES);
    for (int index = payload; index < source.limit(); index++) {
      source.put(index, (byte) 0);
    }
    return StatusCode.OK;
  }

  public StatusCode encodeRequest(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId) {
    if (type == null || type.requiresPayload()) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    return ProtocolFrameWire.begin(target, type, requestId, 0, 0);
  }

  public StatusCode encodeBinaryRequest(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      byte[] payload,
      int payloadBytes) {
    if (target == null || type != ProtocolMessageType.AUTHENTICATE
        || requestId <= 0 || payload == null
        || payloadBytes <= 0 || payloadBytes > payload.length) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    if (payloadBytes > MAXIMUM_PAYLOAD_BYTES) {
      ProtocolFrameWire.empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = ProtocolFrameWire.begin(
        target, type, requestId, payloadBytes, 0);
    if (!status.isOk()) {
      return status;
    }
    for (int index = 0; index < payloadBytes; index++) {
      target.put(HEADER_BYTES + index, payload[index]);
    }
    target.position(0);
    target.limit(HEADER_BYTES + payloadBytes);
    return StatusCode.OK;
  }

  public StatusCode encodeSqlRequest(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      String sql,
      ParameterSet parameters) {
    return sqlRequests.encode(target, type, requestId, sql, parameters);
  }

  public StatusCode encodePreparedRequest(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      long handle,
      ParameterSet parameters) {
    return preparedRequests.encode(target, type, requestId, handle, parameters);
  }

  public StatusCode encodeProgramPrepareRequest(
      ByteBuffer target, long requestId, TransactionProgram program) {
    return programRequests.prepare(target, requestId, program);
  }

  public StatusCode encodeProgramExecuteRequest(
      ByteBuffer target, long requestId, long handle,
      IsolationLevel isolationLevel,
      TransactionProgramArguments arguments) {
    return programRequests.execute(
        target, requestId, handle, isolationLevel, arguments);
  }

  public StatusCode encodeProgramCloseRequest(
      ByteBuffer target, long requestId, long handle) {
    return programRequests.close(target, requestId, handle);
  }

  public StatusCode decodeProgramRequest(
      ProtocolFrame frame, ProtocolProgramRequestDecoder decoder) {
    return decoder == null ? StatusCode.INVALID_EXTERNAL_INPUT : decoder.decode(frame);
  }

  public StatusCode encodeStatusResponse(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      boolean queryActive) {
    return responses.encodeStatus(target, type, requestId, status, queryActive);
  }

  public StatusCode encodeHelloResponse(
      ByteBuffer target,
      long requestId,
      StatusCode status,
      long challengeHigh,
      long challengeLow) {
    return responses.encodeHello(
        target, requestId, status, challengeHigh, challengeLow);
  }

  public StatusCode encodePrepareResponse(
      ByteBuffer target,
      long requestId,
      StatusCode status,
      PreparedOpenResult prepared) {
    return responses.encodePrepared(target, requestId, status, prepared);
  }

  public StatusCode encodeProgramOpenResponse(
      ByteBuffer target, long requestId, StatusCode status, ProgramOpenResult opened) {
    return programOpenResponses.encode(target, requestId, status, opened);
  }

  public StatusCode decodeProgramOpenResponse(
      ByteBuffer source, ProtocolFrame frame, ProgramOpenResult result) {
    StatusCode status = ProtocolFrameWire.decode(
        source, frame, ProtocolFrameWire.ROLE_RESPONSE);
    return status.isOk() ? programOpenResponseDecoder.decode(frame, result) : status;
  }

  public StatusCode decodedProgramOpenStatus() {
    return programOpenResponseDecoder.status();
  }

  public StatusCode encodeProgramResultResponse(
      ByteBuffer target, long requestId, StatusCode status, TransactionProgramResult result) {
    return programResults.encode(
        target, ProtocolMessageType.EXECUTE_PROGRAM, requestId, status, result);
  }

  public static int programResultResponseBytes(
      StatusCode status, TransactionProgramResult result) {
    return ProtocolProgramResultEncoder.requiredWireBytes(status, result);
  }

  public StatusCode decodeProgramResultResponse(
      ByteBuffer source, ProtocolFrame frame, TransactionProgramResult result) {
    return continuedResponses.decodeProgram(
        this, programResultDecoder, source, frame, result);
  }

  public StatusCode decodedProgramResultStatus() {
    return programResultDecoder.status();
  }

  public StatusCode encodeQueryOpenResponse(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      ProtocolQueryMetadata metadata,
      RowResult firstRow,
      long rowsReturned,
      CommandResult completion,
      boolean queryActive) {
    return responses.encodeQueryOpen(
        target, type, requestId, status, metadata, firstRow,
        rowsReturned, completion, queryActive);
  }

  public StatusCode encodeCommandResponse(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      CommandResult command,
      boolean queryActive) {
    return responses.encodeCommand(
        target, type, requestId, status, command, queryActive);
  }

  public StatusCode encodeRowResponse(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      RowResult row,
      long rowsReturned,
      CommandResult completion,
      boolean queryActive) {
    return responses.encodeRow(
        target, type, requestId, status, row, rowsReturned, completion, queryActive);
  }

  public StatusCode decodeResponse(
      ByteBuffer source,
      ProtocolFrame frame,
      ProtocolResponse result) {
    return continuedResponses.decode(this, responseDecoder, source, frame, result);
  }

  public static int continuedResponseWireBytes(int logicalPayloadBytes) {
    return ProtocolContinuationLimits.wireBytes(
        logicalPayloadBytes, MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES);
  }

  public static int continuedRequestWireBytes(int logicalPayloadBytes) {
    return ProtocolContinuationLimits.wireBytes(
        logicalPayloadBytes, MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES);
  }

  public StatusCode decodeAssembledRequest(ByteBuffer source, ProtocolFrame result) {
    return ProtocolAssembledRequestDecoder.decode(source, result);
  }

}
