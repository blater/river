package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.protocol.ProtocolMessageType;

/** Encodes a request, growing retained storage only for SQL continuations. */
final class RiverClientRequestEncoder {
  private RiverClientRequestEncoder() { }

  static StatusCode encode(RiverClientConnection connection, ProtocolMessageType type,
      long requestId, String text, ParameterSet parameters, byte[] payload, int payloadBytes,
      long preparedHandle) {
    if (preparedHandle > 0) {
      StatusCode status;
      do {
        status = connection.codec.encodePreparedRequest(
            connection.request, type, requestId, preparedHandle, parameters);
      } while (status == StatusCode.RESOURCE_EXHAUSTED
          && connection.growRequestBytes().isOk());
      return status;
    }
    if (payload != null) return connection.codec.encodeBinaryRequest(
        connection.request, type, requestId, payload, payloadBytes);
    if (text == null) return connection.codec.encodeRequest(
        connection.request, type, requestId);
    StatusCode status;
    do {
      status = connection.codec.encodeSqlRequest(
          connection.request, type, requestId, text, parameters);
    } while (status == StatusCode.RESOURCE_EXHAUSTED
        && connection.growRequestBytes().isOk());
    return status;
  }
}
