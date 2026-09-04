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
      boolean close = type == ProtocolMessageType.CLOSE_PREPARED;
      StatusCode status;
      do {
        status = connection.codec.encodePreparedRequest(
            connection.request, type, requestId, preparedHandle, parameters,
            close ? 0 : connection.diagnosticTag,
            close ? 0 : connection.diagnosticStepTag,
            close ? 0 : connection.metricsEpoch);
      } while (status == StatusCode.RESOURCE_EXHAUSTED
          && connection.growRequestBytes().isOk());
      return status;
    }
    if (payload != null) return connection.codec.encodeBinaryRequest(
        connection.request, type, requestId, payload, payloadBytes);
    if (text == null) return connection.codec.encodeRequest(
        connection.request, type, requestId);
    StatusCode status;
    boolean prepare = type == ProtocolMessageType.PREPARE;
    do {
      status = connection.codec.encodeSqlRequest(
          connection.request, type, requestId, text, parameters,
          prepare ? 0 : connection.diagnosticTag,
          prepare ? 0 : connection.diagnosticStepTag,
          prepare ? 0 : connection.metricsEpoch);
    } while (status == StatusCode.RESOURCE_EXHAUSTED
        && connection.growRequestBytes().isOk());
    return status;
  }
}
