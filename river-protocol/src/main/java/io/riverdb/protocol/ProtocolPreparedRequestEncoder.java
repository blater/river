package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import java.nio.ByteBuffer;

/** Encodes a prepared handle plus typed values without SQL text. */
final class ProtocolPreparedRequestEncoder {
  // Parameter count is the protocol's unsigned 16-bit field, not a session admission limit.
  private static final int HEADER_BYTES = Long.BYTES + Short.BYTES * 2
      + ProtocolTransactionDiagnosticContext.BYTES;

  StatusCode encode(ByteBuffer target, ProtocolMessageType type, long requestId,
      long handle, ParameterSet parameters,
      long diagnosticTag, long diagnosticStepTag, long metricsEpoch) {
    boolean close = type == ProtocolMessageType.CLOSE_PREPARED;
    if (!validRequest(
        target, type, requestId, handle, parameters, close,
        diagnosticTag, diagnosticStepTag, metricsEpoch)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int count = parameterCount(parameters, close);
    long payload = payloadBytes(parameters, close, count);
    if (payload < 0) {
      ProtocolFrameWire.empty(target);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int payloadBytes = (int) payload;
    StatusCode status = ProtocolRequestSegmenter.prepare(target, payloadBytes);
    if (!status.isOk()) return status;
    status = ProtocolFrameWire.begin(target, type, requestId, payloadBytes, 0);
    if (!status.isOk()) return status;
    int output = ProtocolFrameCodec.HEADER_BYTES;
    target.putLong(output, handle);
    output += Long.BYTES;
    if (!close) {
      target.putShort(output, (short) count);
      target.putShort(output + Short.BYTES, (short) 0);
      output = ProtocolTransactionDiagnosticContext.write(
          target, output + Short.BYTES * 2,
          diagnosticTag, diagnosticStepTag, metricsEpoch);
      output = writeParameters(target, output, parameters, count);
    }
    return ProtocolRequestSegmenter.finish(target, type, requestId, payloadBytes);
  }

  private static boolean validRequest(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      long handle,
      ParameterSet parameters,
      boolean close,
      long diagnosticTag,
      long diagnosticStepTag,
      long metricsEpoch) {
    if (target == null || requestId <= 0 || handle <= 0) return false;
    if (!validType(type, close)) return false;
    if (close ? parameters != null : parameters == null) return false;
    if (!ProtocolTransactionDiagnosticContext.valid(
        diagnosticTag, diagnosticStepTag, metricsEpoch)) return false;
    return !close || diagnosticTag == 0 && diagnosticStepTag == 0 && metricsEpoch == 0;
  }

  private static boolean validType(ProtocolMessageType type, boolean close) {
    if (close) return type == ProtocolMessageType.CLOSE_PREPARED;
    return type == ProtocolMessageType.EXECUTE_PREPARED
        || type == ProtocolMessageType.BEGIN_PREPARED_QUERY;
  }

  private static int parameterCount(ParameterSet parameters, boolean close) {
    return close ? 0 : parameters.count();
  }

  private static long payloadBytes(ParameterSet parameters, boolean close, int count) {
    long payload = close ? Long.BYTES : HEADER_BYTES;
    for (int index = 0; index < count; index++) {
      int bytes = ProtocolParameterEncoder.valueBytes(parameters, index);
      if (bytes < 0) return -1;
      payload += ProtocolValueHeader.BYTES + bytes;
    }
    return payload;
  }

  private static int writeParameters(
      ByteBuffer target, int output, ParameterSet parameters, int count) {
    for (int index = 0; index < count; index++) {
      output = ProtocolParameterEncoder.writeParameter(target, output, parameters, index);
    }
    return output;
  }
}
