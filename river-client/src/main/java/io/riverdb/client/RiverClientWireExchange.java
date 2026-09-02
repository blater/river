package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import java.io.IOException;
import java.util.Arrays;

/** Sends one encoded request and completes its matching ordered response. */
final class RiverClientWireExchange {
  private static final int STANDARD = 0;
  private static final int PROGRAM_OPEN = 1;
  private static final int PROGRAM_RESULT = 2;

  private RiverClientWireExchange() { }

  static StatusCode standard(
      RiverClientConnection connection, ProtocolMessageType type, long requestId) {
    return transfer(connection, type, requestId, STANDARD, null, null);
  }

  static StatusCode programOpen(
      RiverClientConnection connection, long requestId, ProgramOpenResult result) {
    return transfer(connection, ProtocolMessageType.PREPARE_PROGRAM,
        requestId, PROGRAM_OPEN, result, null);
  }

  static StatusCode programResult(
      RiverClientConnection connection, long requestId, TransactionProgramResult result) {
    return transfer(connection, ProtocolMessageType.EXECUTE_PROGRAM,
        requestId, PROGRAM_RESULT, null, result);
  }

  private static StatusCode transfer(
      RiverClientConnection connection, ProtocolMessageType type, long requestId,
      int responseKind, ProgramOpenResult opened, TransactionProgramResult result) {
    try {
      connection.responseFullyRead = false;
      int requestBytes = connection.request.remaining();
      try {
        connection.output.write(connection.request.array(), 0, requestBytes);
        connection.output.flush();
      } finally {
        if (type.requiresPayload()) {
          Arrays.fill(connection.request.array(), ProtocolFrameCodec.HEADER_BYTES,
              requestBytes, (byte) 0);
        }
      }
      connection.bytesSent += requestBytes;
      StatusCode status = read(
          connection, type, requestId, responseKind, opened, result);
      if (!status.isOk()) {
        if (responseKind != PROGRAM_RESULT || status != StatusCode.RESOURCE_EXHAUSTED
            || !connection.responseFullyRead) return connection.fail(status);
        connection.nextRequestId++;
        connection.completedRequests++;
        connection.lastStatus = status;
        return status;
      }
      connection.nextRequestId++;
      connection.completedRequests++;
      connection.lastStatus = responseStatus(connection, responseKind);
      return StatusCode.OK;
    } catch (IOException failure) {
      return connection.fail(
          connection.cancelled ? StatusCode.CANCELLED : StatusCode.IO_FAILURE);
    }
  }

  private static StatusCode read(
      RiverClientConnection connection, ProtocolMessageType type, long requestId,
      int responseKind, ProgramOpenResult opened, TransactionProgramResult result)
      throws IOException {
    return switch (responseKind) {
      case STANDARD -> RiverClientResponseReader.read(connection, type, requestId);
      case PROGRAM_OPEN -> RiverClientResponseReader.readProgramOpen(
          connection, requestId, opened);
      case PROGRAM_RESULT -> RiverClientResponseReader.readProgramResult(
          connection, requestId, result);
      default -> StatusCode.INVARIANT_BROKEN;
    };
  }

  private static StatusCode responseStatus(
      RiverClientConnection connection, int responseKind) {
    return switch (responseKind) {
      case STANDARD -> connection.response.status();
      case PROGRAM_OPEN -> connection.codec.decodedProgramOpenStatus();
      case PROGRAM_RESULT -> connection.codec.decodedProgramResultStatus();
      default -> StatusCode.INVARIANT_BROKEN;
    };
  }
}
