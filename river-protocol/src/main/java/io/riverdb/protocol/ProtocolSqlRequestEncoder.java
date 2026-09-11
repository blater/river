package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import java.nio.ByteBuffer;

/** Encodes one SQL text plus typed-parameter request without intermediate storage. */
final class ProtocolSqlRequestEncoder {
  private static final int REQUEST_HEADER_BYTES = Integer.BYTES + Short.BYTES * 2
      + ProtocolTransactionDiagnosticContext.BYTES;
  private static final int ENTRY_HEADER_BYTES = ProtocolValueHeader.BYTES;

  StatusCode encode(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      String sql,
      ParameterSet parameters,
      long diagnosticTag,
      long diagnosticStepTag,
      long metricsEpoch) {
    if (target == null || !sqlType(type) || requestId <= 0
        || sql == null || sql.isEmpty()
        || !ProtocolTransactionDiagnosticContext.valid(
            diagnosticTag, diagnosticStepTag, metricsEpoch)
        || type == ProtocolMessageType.PREPARE
            && (diagnosticTag != 0 || diagnosticStepTag != 0 || metricsEpoch != 0)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int sqlBytes = ProtocolSqlTextEncoder.sqlBytes(sql);
    if (sqlBytes < 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int parameterCount = parameters == null ? 0 : parameters.count();
    if (parameterCount < 0 || parameterCount > ParameterSet.MAXIMUM_PARAMETERS
        || type == ProtocolMessageType.PREPARE && parameterCount != 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    long payload = (long) REQUEST_HEADER_BYTES + sqlBytes;
    for (int index = 0; index < parameterCount; index++) {
      int valueBytes = ProtocolParameterEncoder.valueBytes(parameters, index);
      if (valueBytes < 0) {
        ProtocolFrameWire.empty(target);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      payload += ENTRY_HEADER_BYTES + valueBytes;
    }
    int payloadBytes = (int) payload;
    StatusCode prepared = ProtocolRequestSegmenter.prepare(target, payloadBytes);
    if (!prepared.isOk()) return prepared;
    StatusCode status = ProtocolFrameWire.begin(
        target, type, requestId, payloadBytes, 0);
    if (!status.isOk()) {
      return status;
    }
    int output = ProtocolFrameCodec.HEADER_BYTES;
    target.putInt(output, sqlBytes);
    target.putShort(output + Integer.BYTES, (short) parameterCount);
    target.putShort(output + Integer.BYTES + Short.BYTES, (short) 0);
    output = ProtocolTransactionDiagnosticContext.write(
        target, output + Integer.BYTES + Short.BYTES * 2,
        diagnosticTag, diagnosticStepTag, metricsEpoch);
    output = ProtocolSqlTextEncoder.writeSql(target, output, sql);
    for (int index = 0; index < parameterCount; index++) {
      output = ProtocolParameterEncoder.writeParameter(target, output, parameters, index);
    }
    return ProtocolRequestSegmenter.finish(target, type, requestId, payloadBytes);
  }

  private static boolean sqlType(ProtocolMessageType type) {
    return type == ProtocolMessageType.EXECUTE
        || type == ProtocolMessageType.BEGIN_QUERY
        || type == ProtocolMessageType.PREPARE;
  }
}
