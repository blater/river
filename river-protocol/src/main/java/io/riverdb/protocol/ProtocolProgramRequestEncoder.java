package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import java.nio.ByteBuffer;

/** Emits PREPARE_PROGRAM, EXECUTE_PROGRAM, and CLOSE_PROGRAM requests. */
final class ProtocolProgramRequestEncoder {
  private static final int EXECUTE_HEADER_BYTES = Long.BYTES + Integer.BYTES * 2
      + ProtocolTransactionDiagnosticContext.BYTES;

  StatusCode prepare(ByteBuffer target, long requestId, TransactionProgram program) {
    return ProtocolProgramGraphCodec.encode(target, requestId, program);
  }

  StatusCode execute(
      ByteBuffer target, long requestId, long handle,
      IsolationLevel isolationLevel, TransactionProgramArguments arguments,
      long diagnosticTag, long diagnosticStepTag, long metricsEpoch) {
    if (target == null || requestId <= 0 || handle <= 0
        || isolationLevel == null || arguments == null
        || !ProtocolTransactionDiagnosticContext.valid(
            diagnosticTag, diagnosticStepTag, metricsEpoch)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int argumentCount = arguments.slotCount();
    long payload = EXECUTE_HEADER_BYTES;
    for (int index = 0; index < argumentCount; index++) {
      if (!arguments.isSet(index)) {
        ProtocolFrameWire.empty(target);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int bytes = ProtocolSqlRequestEncoder.valueBytes(arguments, index);
      if (bytes < 0) {
        ProtocolFrameWire.empty(target);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (payload > ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES
          - EXECUTE_HEADER_BYTES - ProtocolValueHeader.BYTES - bytes) {
        ProtocolFrameWire.empty(target);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      payload += ProtocolValueHeader.BYTES + bytes;
    }
    if (payload > ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES) {
      ProtocolFrameWire.empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int payloadBytes = (int) payload;
    StatusCode status = ProtocolRequestSegmenter.prepare(target, payloadBytes);
    if (!status.isOk()) return status;
    status = ProtocolFrameWire.begin(
        target, ProtocolMessageType.EXECUTE_PROGRAM, requestId, payloadBytes, 0);
    if (!status.isOk()) return status;
    int output = ProtocolFrameCodec.HEADER_BYTES;
    target.putLong(output, handle);
    target.putInt(output + Long.BYTES, argumentCount);
    target.putInt(
        output + Long.BYTES + Integer.BYTES,
        ProtocolIsolationLevelCodec.encode(isolationLevel));
    output = ProtocolTransactionDiagnosticContext.write(
        target, output + Long.BYTES + Integer.BYTES * 2,
        diagnosticTag, diagnosticStepTag, metricsEpoch);
    for (int index = 0; index < argumentCount; index++) {
      output = ProtocolSqlRequestEncoder.writeParameter(target, output, arguments, index);
    }
    return ProtocolRequestSegmenter.finish(
        target, ProtocolMessageType.EXECUTE_PROGRAM, requestId, payloadBytes);
  }

  StatusCode close(ByteBuffer target, long requestId, long handle) {
    if (target == null || requestId <= 0 || handle <= 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    StatusCode status = ProtocolFrameWire.begin(
        target, ProtocolMessageType.CLOSE_PROGRAM, requestId, Long.BYTES, 0);
    if (!status.isOk()) return status;
    target.putLong(ProtocolFrameCodec.HEADER_BYTES, handle);
    target.position(0);
    target.limit(ProtocolFrameCodec.HEADER_BYTES + Long.BYTES);
    return StatusCode.OK;
  }
}
