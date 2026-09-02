package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgram;
import java.nio.ByteBuffer;

/** Validates and segments PREPARE_PROGRAM graph requests. */
final class ProtocolProgramGraphEncoder {
  private ProtocolProgramGraphEncoder() { }

  static StatusCode encode(ByteBuffer target, long requestId, TransactionProgram program) {
    if (target == null || requestId <= 0 || program == null || !program.isFrozen()) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int steps = program.stepCount();
    int expressions = ProtocolProgramGraphShape.expressions(program);
    int captures = ProtocolProgramGraphShape.captures(program);
    int nodes = program.nodeCount();
    int arguments = program.requiredArgumentSlots();
    if (steps <= 0 || expressions < 0 || captures < 0 || nodes < 0 || arguments < 0
        || !ProtocolProgramGraphValidation.valid(program, expressions, captures, nodes, arguments)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    long payload = ProtocolProgramGraphShape.payloadBytes(steps, expressions, nodes, captures);
    if (payload > ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES) {
      ProtocolFrameWire.empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int payloadBytes = (int) payload;
    StatusCode status = ProtocolRequestSegmenter.prepare(target, payloadBytes);
    if (!status.isOk()) return status;
    status = ProtocolFrameWire.begin(
        target, ProtocolMessageType.PREPARE_PROGRAM, requestId, payloadBytes, 0);
    if (!status.isOk()) return status;
    ProtocolProgramGraphWriter.write(
        target, ProtocolFrameCodec.HEADER_BYTES, program, expressions);
    return ProtocolRequestSegmenter.finish(
        target, ProtocolMessageType.PREPARE_PROGRAM, requestId, payloadBytes);
  }
}
