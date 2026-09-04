package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.RetainedMemoryLease;
import java.nio.ByteBuffer;

/** Reusable strict decoder for prepared-handle requests. */
public final class ProtocolPreparedRequestDecoder {
  // Parameter count is the protocol's unsigned 16-bit field, not a session admission limit.
  private static final int HEADER_BYTES = Long.BYTES + Short.BYTES * 2
      + ProtocolTransactionDiagnosticContext.BYTES;
  private final ProtocolParameterDecoder parameterDecoder;
  private long handle;
  private long diagnosticTag;
  private long diagnosticStepTag;
  private long metricsEpoch;

  public ProtocolPreparedRequestDecoder() {
    this(RetainedMemoryLease.unbounded());
  }

  public ProtocolPreparedRequestDecoder(RetainedMemoryLease memory) {
    parameterDecoder = new ProtocolParameterDecoder(memory);
  }

  public StatusCode decode(ProtocolFrame frame) {
    reset();
    if (frame == null || frame.isResponse()) return fail(frame);
    boolean close = frame.type() == ProtocolMessageType.CLOSE_PREPARED;
    boolean execution = frame.type() == ProtocolMessageType.EXECUTE_PREPARED
        || frame.type() == ProtocolMessageType.BEGIN_PREPARED_QUERY;
    if (!close && !execution || frame.payloadBytes() < (close ? Long.BYTES : HEADER_BYTES)) {
      return fail(frame);
    }
    ByteBuffer source = frame.source();
    int input = frame.payloadOffset();
    int end = input + frame.payloadBytes();
    handle = source.getLong(input);
    input += Long.BYTES;
    if (handle <= 0 || close && input != end) return fail(frame);
    if (!close) {
      int count = Short.toUnsignedInt(source.getShort(input));
      int reserved = Short.toUnsignedInt(source.getShort(input + Short.BYTES));
      diagnosticTag = source.getLong(input + Short.BYTES * 2);
      diagnosticStepTag = source.getLong(input + Short.BYTES * 2 + Long.BYTES);
      metricsEpoch = source.getLong(input + Short.BYTES * 2 + Long.BYTES * 2);
      input += Short.BYTES * 2 + ProtocolTransactionDiagnosticContext.BYTES;
      if (reserved != 0 || count > ParameterSet.MAXIMUM_PARAMETERS
          || !ProtocolTransactionDiagnosticContext.valid(
              diagnosticTag, diagnosticStepTag, metricsEpoch)) return fail(frame);
      StatusCode status = parameterDecoder.decode(source, input, end, count);
      if (!status.isOk()) return failure(frame, status);
    }
    StatusCode erased = frame.erasePayload();
    if (!erased.isOk()) reset();
    return erased;
  }

  public long handle() { return handle; }
  public ParameterSet parameters() { return parameterDecoder.parameters(); }
  public long diagnosticTag() { return diagnosticTag; }
  public long diagnosticStepTag() { return diagnosticStepTag; }
  public long metricsEpoch() { return metricsEpoch; }
  public void reset() {
    handle = 0;
    diagnosticTag = 0;
    diagnosticStepTag = 0;
    metricsEpoch = 0;
    parameterDecoder.reset();
  }
  public StatusCode releaseHighWater() {
    handle = 0;
    return parameterDecoder.releaseHighWater();
  }
  public StatusCode release() {
    handle = 0;
    return parameterDecoder.release();
  }

  private StatusCode fail(ProtocolFrame frame) {
    return failure(frame, StatusCode.INVALID_EXTERNAL_INPUT);
  }

  private StatusCode failure(ProtocolFrame frame, StatusCode status) {
    if (frame != null) frame.erasePayload();
    reset();
    return status;
  }
}
