package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import java.io.IOException;
import java.util.Arrays;

/** Encodes one request and validates its ordered response. */
final class RiverClientExchange {
  private RiverClientExchange() { }

  static StatusCode exchange(
      RiverClientConnection connection,
      ProtocolMessageType type,
      String text,
      ParameterSet parameters,
      byte[] payload,
      int payloadBytes) {
    if (connection.closed) return StatusCode.CLOSED;
    if (connection.nextRequestId <= 0 || connection.nextRequestId == Long.MAX_VALUE) {
      return connection.fail(StatusCode.FENCED);
    }
    long requestId = connection.nextRequestId;
    StatusCode status;
    if (payload != null) {
      status = connection.codec.encodeBinaryRequest(
          connection.request, type, requestId, payload, payloadBytes);
    } else if (text != null) {
      status = connection.codec.encodeSqlRequest(
          connection.request, type, requestId, text, parameters);
    } else {
      status = connection.codec.encodeRequest(connection.request, type, requestId);
    }
    if (!status.isOk()) return status;
    try {
      int requestBytes = connection.request.remaining();
      try {
        connection.output.write(connection.request.array(), 0, requestBytes);
        connection.output.flush();
      } finally {
        if (type.requiresPayload()) {
          Arrays.fill(
              connection.request.array(),
              ProtocolFrameCodec.HEADER_BYTES,
              requestBytes,
              (byte) 0);
        }
      }
      connection.bytesSent += requestBytes;
      if (!RiverClientConnection.readExact(
          connection.input, connection.responseBytes, 0, ProtocolFrameCodec.HEADER_BYTES)) {
        return connection.fail(StatusCode.IO_FAILURE);
      }
      connection.responseBuffer.position(0);
      connection.responseBuffer.limit(ProtocolFrameCodec.HEADER_BYTES);
      status = connection.codec.inspectResponseHeader(
          connection.responseBuffer, connection.responseHeader);
      if (!status.isOk()
          || connection.responseHeader.typeWireCode() != type.wireCode()
          || connection.responseHeader.requestId() != requestId) {
        return connection.fail(StatusCode.CORRUPTION);
      }
      int responsePayloadBytes = connection.responseHeader.payloadBytes();
      if (!RiverClientConnection.readExact(
          connection.input,
          connection.responseBytes,
          ProtocolFrameCodec.HEADER_BYTES,
          responsePayloadBytes)) {
        return connection.fail(StatusCode.IO_FAILURE);
      }
      connection.responseBuffer.position(0);
      connection.responseBuffer.limit(ProtocolFrameCodec.HEADER_BYTES + responsePayloadBytes);
      status = connection.codec.decodeResponse(
          connection.responseBuffer, connection.frame, connection.response);
      if (!status.isOk()
          || connection.frame.type() != type
          || connection.frame.requestId() != requestId) {
        return connection.fail(StatusCode.CORRUPTION);
      }
      connection.nextRequestId++;
      connection.completedRequests++;
      connection.bytesReceived += ProtocolFrameCodec.HEADER_BYTES + responsePayloadBytes;
      connection.lastStatus = connection.response.status();
      return StatusCode.OK;
    } catch (IOException failure) {
      return connection.fail(
          connection.cancelled ? StatusCode.CANCELLED : StatusCode.IO_FAILURE);
    }
  }
}
