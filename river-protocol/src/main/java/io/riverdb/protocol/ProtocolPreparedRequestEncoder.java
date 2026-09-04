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
    if (target == null || requestId <= 0 || handle <= 0
        || !close && type != ProtocolMessageType.EXECUTE_PREPARED
            && type != ProtocolMessageType.BEGIN_PREPARED_QUERY
        || close && parameters != null || !close && parameters == null
        || !ProtocolTransactionDiagnosticContext.valid(
            diagnosticTag, diagnosticStepTag, metricsEpoch)
        || close && (diagnosticTag != 0 || diagnosticStepTag != 0 || metricsEpoch != 0)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int count = close ? 0 : parameters.count();
    long payload = close ? Long.BYTES : HEADER_BYTES;
    for (int index = 0; index < count; index++) {
      int bytes = ProtocolSqlRequestEncoder.valueBytes(parameters, index);
      if (bytes < 0) {
        ProtocolFrameWire.empty(target);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      payload += ProtocolValueHeader.BYTES + bytes;
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
      for (int index = 0; index < count; index++) {
        output = ProtocolSqlRequestEncoder.writeParameter(target, output, parameters, index);
      }
    }
    return ProtocolRequestSegmenter.finish(target, type, requestId, payloadBytes);
  }
}
