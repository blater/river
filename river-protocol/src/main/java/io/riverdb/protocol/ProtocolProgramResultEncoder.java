package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.ByteBuffer;

/** Encodes the complete ordered result and diagnostics of one program call. */
final class ProtocolProgramResultEncoder {
  static final int FORMAT = 1;
  static final int HEADER_BYTES = 64;
  static final int STEP_BYTES = 16;
  static final int ROW_BYTES = 8;
  static final int VALUE_HEADER_BYTES = Integer.BYTES + Byte.BYTES * 2 + Short.BYTES;
  static final int FLAG_FENCED = 1;

  static int requiredWireBytes(StatusCode outerStatus, TransactionProgramResult result) {
    if (outerStatus == null || result == null && outerStatus.isOk()) return 0;
    int steps = ProtocolProgramResultHeader.steps(result);
    int rows = ProtocolProgramResultHeader.rows(result);
    int cells = ProtocolProgramResultHeader.cells(result);
    if (steps < 0 || rows < 0 || cells < 0
        || !ProtocolProgramResultShape.valid(result, outerStatus, steps, rows, cells)) return 0;
    long payload = result == null ? HEADER_BYTES
        : ProtocolProgramResultShape.payloadBytes(result, steps, rows);
    if (payload > ProtocolFrameCodec.MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES) return -1;
    int payloadBytes = (int) payload;
    return payloadBytes <= ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
        ? ProtocolFrameCodec.HEADER_BYTES + payloadBytes
        : ProtocolFrameCodec.continuedResponseWireBytes(payloadBytes);
  }

  StatusCode encode(
      ByteBuffer target, ProtocolMessageType type, long requestId,
      StatusCode outerStatus, TransactionProgramResult result) {
    if (!ProtocolProgramResultHeader.validTarget(
        target, type, requestId, outerStatus, result)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int required = requiredWireBytes(outerStatus, result);
    if (required <= 0) {
      ProtocolFrameWire.empty(target);
      return required < 0
          ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (target.capacity() < required) return ProtocolFrameWire.exhaustedTarget(target);
    int steps = ProtocolProgramResultHeader.steps(result);
    int rows = ProtocolProgramResultHeader.rows(result);
    int cells = ProtocolProgramResultHeader.cells(result);
    long payload = result == null ? HEADER_BYTES
        : ProtocolProgramResultShape.payloadBytes(result, steps, rows);
    int payloadBytes = (int) payload;
    StatusCode status = ProtocolFrameWire.begin(
        target, type, requestId, payloadBytes, ProtocolFrameWire.FRAME_RESPONSE);
    if (!status.isOk()) return status;
    int offset = ProtocolFrameCodec.HEADER_BYTES;
    ProtocolProgramResultHeader.write(
        target, offset, outerStatus, result, steps, rows, cells);
    offset += HEADER_BYTES;
    if (result != null) ProtocolProgramResultWriter.write(target, offset, result, steps);
    return ProtocolResponseSegmenter.finish(target, type, requestId, payloadBytes);
  }

}
