package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.protocol.ProtocolMessageType;

/** Encodes one request and validates its ordered response. */
final class RiverClientExchange {
  private RiverClientExchange() { }

  static StatusCode exchange(
      RiverClientConnection connection,
      ProtocolMessageType type,
      String text,
      ParameterSet parameters,
      byte[] payload,
      int payloadBytes,
      long preparedHandle) {
    if (connection.closed) return StatusCode.CLOSED;
    if (connection.nextRequestId <= 0 || connection.nextRequestId == Long.MAX_VALUE) {
      return connection.fail(StatusCode.FENCED);
    }
    long requestId = connection.nextRequestId;
    StatusCode status = RiverClientRequestEncoder.encode(
        connection, type, requestId, text, parameters, payload, payloadBytes, preparedHandle);
    if (!status.isOk()) return status;
    return RiverClientWireExchange.standard(connection, type, requestId);
  }
}
