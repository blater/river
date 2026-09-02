package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.ByteBuffer;

/** Reusable strict decoder for an EXECUTE_PROGRAM result frame. */
public final class ProtocolProgramResultDecoder {
  private final ProtocolProgramTextDecoder text;
  private final ProtocolProgramResultHeaderDecoder header =
      new ProtocolProgramResultHeaderDecoder();
  private StatusCode outerStatus;

  public ProtocolProgramResultDecoder() {
    this(RetainedMemoryLease.unbounded());
  }

  public ProtocolProgramResultDecoder(RetainedMemoryLease memory) {
    text = new ProtocolProgramTextDecoder(memory);
  }

  public StatusCode decode(ProtocolFrame frame, TransactionProgramResult result) {
    if (frame == null || !frame.isResponse() || result == null
        || frame.type() != ProtocolMessageType.EXECUTE_PROGRAM) return invalid(result);
    return decodePayload(frame.source(), frame.payloadOffset(),
        frame.payloadOffset() + frame.payloadBytes(), result);
  }

  StatusCode decode(ByteBuffer source, ProtocolFrame frame, TransactionProgramResult result) {
    if (source == null || frame == null || result == null) return invalid(result);
    result.reset();
    StatusCode status = ProtocolFrameWire.decode(
        source, frame, ProtocolFrameWire.ROLE_RESPONSE);
    return status.isOk() ? decode(frame, result) : invalid(result, status);
  }

  StatusCode decodeAssembled(
      ByteBuffer source, ProtocolFrame frame, TransactionProgramResult result) {
    if (source == null || frame == null || result == null) return invalid(result);
    frame.reset();
    int payloadBytes = source.remaining() - ProtocolFrameCodec.HEADER_BYTES;
    if (payloadBytes <= ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
        || payloadBytes > ProtocolFrameCodec.MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES) {
      return invalid(result);
    }
    ProtocolMessageType type = ProtocolMessageType.fromWireCode(source.getInt(8));
    if (type != ProtocolMessageType.EXECUTE_PROGRAM || source.getLong(16) <= 0) {
      return invalid(result);
    }
    frame.complete(source, type, source.getLong(16), ProtocolFrameCodec.HEADER_BYTES,
        payloadBytes, true);
    return decodePayload(source, ProtocolFrameCodec.HEADER_BYTES,
        ProtocolFrameCodec.HEADER_BYTES + payloadBytes, result);
  }

  public StatusCode status() { return outerStatus; }
  public void reset() { outerStatus = null; header.reset(); text.reset(); }

  public StatusCode releaseHighWater() { return text.releaseHighWater(); }
  public StatusCode release() { return text.release(); }

  private StatusCode decodePayload(
      ByteBuffer source, int offset, int end, TransactionProgramResult result) {
    reset();
    result.reset();
    if (source == null || offset < 0 || end < offset || end > source.limit()
        || end - offset < ProtocolProgramResultEncoder.HEADER_BYTES) return invalid(result);
    StatusCode headerStatus = header.decode(source, offset, end);
    if (!headerStatus.isOk()) return invalid(result, headerStatus);
    int steps = header.steps();
    int rows = header.rows();
    int cells = header.cells();
    int input = offset + ProtocolProgramResultEncoder.HEADER_BYTES;
    if (ProtocolProgramResultStructure.validateSteps(
        source, input, end, steps, rows, cells) != end) return invalid(result);
    outerStatus = header.outer();
    input = offset + ProtocolProgramResultEncoder.HEADER_BYTES;
    for (int step = 0; step < steps; step++) {
      StatusCode status = result.beginStepResult(
          source.getInt(input), source.getInt(input + 4), source.getInt(input + 8));
      if (!status.isOk()) return invalid(result, status);
      int rowCount = source.getInt(input + 12);
      input += ProtocolProgramResultEncoder.STEP_BYTES;
      status = ProtocolProgramResultRows.populate(source, input, rowCount, result, text);
      if (!status.isOk()) return invalid(result, status);
      input = ProtocolProgramResultRows.next(source, input, rowCount);
    }
    if (header.failing() >= 0) {
      result.fail(header.failing(), header.primary(), header.rollback(),
          (header.flags() & ProtocolProgramResultEncoder.FLAG_FENCED) != 0);
    } else {
      result.complete(header.commit());
    }
    return StatusCode.OK;
  }

  private StatusCode invalid(TransactionProgramResult result) {
    return invalid(result, StatusCode.INVALID_EXTERNAL_INPUT);
  }

  private StatusCode invalid(TransactionProgramResult result, StatusCode status) {
    if (result != null) result.reset();
    outerStatus = null;
    text.reset();
    return status;
  }
}
