package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import java.io.IOException;

/** Reads, reassembles, and validates one ordered server response. */
final class RiverClientResponseReader {
  private RiverClientResponseReader() { }

  static StatusCode read(
      RiverClientConnection connection, ProtocolMessageType type, long requestId)
      throws IOException {
    StatusCode status = readFrames(connection, type, requestId);
    if (!status.isOk()) return status;
    status = connection.codec.decodeResponse(
        connection.responseBuffer, connection.frame, connection.response);
    if (!status.isOk() || connection.frame.type() != type
        || connection.frame.requestId() != requestId) return StatusCode.CORRUPTION;
    connection.bytesReceived += connection.responseBuffer.limit();
    return StatusCode.OK;
  }

  static StatusCode readProgramOpen(
      RiverClientConnection connection, long requestId, ProgramOpenResult result)
      throws IOException {
    StatusCode status = readFrames(
        connection, ProtocolMessageType.PREPARE_PROGRAM, requestId);
    if (!status.isOk()) return status;
    status = connection.codec.decodeProgramOpenResponse(
        connection.responseBuffer, connection.frame, result);
    return completeProgramDecode(
        connection, ProtocolMessageType.PREPARE_PROGRAM, requestId, status);
  }

  static StatusCode readProgramResult(
      RiverClientConnection connection, long requestId, TransactionProgramResult result)
      throws IOException {
    StatusCode status = readFrames(
        connection, ProtocolMessageType.EXECUTE_PROGRAM, requestId);
    if (!status.isOk()) return status;
    status = connection.codec.decodeProgramResultResponse(
        connection.responseBuffer, connection.frame, result);
    return completeProgramDecode(
        connection, ProtocolMessageType.EXECUTE_PROGRAM, requestId, status);
  }

  private static StatusCode readFrames(
      RiverClientConnection connection, ProtocolMessageType type, long requestId)
      throws IOException {
    if (!RiverClientConnection.readExact(connection.input, connection.responseBytes,
        0, ProtocolFrameCodec.HEADER_BYTES)) return StatusCode.IO_FAILURE;
    connection.responseBuffer.position(0);
    connection.responseBuffer.limit(ProtocolFrameCodec.HEADER_BYTES);
    StatusCode status = connection.codec.inspectResponseHeader(
        connection.responseBuffer, connection.responseHeader);
    if (!status.isOk() || connection.responseHeader.typeWireCode() != type.wireCode()
        || connection.responseHeader.requestId() != requestId) return StatusCode.CORRUPTION;
    int payload = connection.responseHeader.payloadBytes();
    status = connection.reserveResponseBytes(ProtocolFrameCodec.HEADER_BYTES + payload);
    if (!status.isOk()) return status;
    if (!RiverClientConnection.readExact(connection.input, connection.responseBytes,
        ProtocolFrameCodec.HEADER_BYTES, payload)) return StatusCode.IO_FAILURE;
    connection.responseBuffer.position(0);
    connection.responseBuffer.limit(ProtocolFrameCodec.HEADER_BYTES + payload);
    if (connection.responseHeader.isContinuation()) {
      status = RiverResponseContinuation.read(connection, type, requestId, payload);
      if (!status.isOk()) return status;
    }
    connection.responseFullyRead = true;
    return StatusCode.OK;
  }

  private static StatusCode completeProgramDecode(
      RiverClientConnection connection, ProtocolMessageType type,
      long requestId, StatusCode status) {
    if (!status.isOk()) return status;
    if (connection.frame.type() != type
        || connection.frame.requestId() != requestId) return StatusCode.CORRUPTION;
    connection.bytesReceived += connection.responseBuffer.limit();
    return StatusCode.OK;
  }
}
