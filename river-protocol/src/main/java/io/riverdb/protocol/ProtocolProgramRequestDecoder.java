package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.RetainedMemoryLease;
import java.nio.ByteBuffer;

/** Reusable strict decoder for all program lifecycle requests. */
public final class ProtocolProgramRequestDecoder {
  private static final int EXECUTE_HEADER_BYTES = Long.BYTES + Integer.BYTES * 2;
  private static final int CLOSE_HEADER_BYTES = Long.BYTES;
  private final ProtocolPartitionedLease memory;
  private final TransactionProgram program;
  private final TransactionProgramArguments arguments;
  private final ProtocolProgramTextDecoder text;
  private long handle;

  public ProtocolProgramRequestDecoder() {
    this(RetainedMemoryLease.unbounded());
  }

  public ProtocolProgramRequestDecoder(RetainedMemoryLease memory) {
    this(new ProtocolPartitionedLease(memory, 3));
  }

  private ProtocolProgramRequestDecoder(ProtocolPartitionedLease memory) {
    this.memory = memory;
    program = new TransactionProgram(memory.lane(0));
    arguments = new TransactionProgramArguments(memory.lane(1));
    text = new ProtocolProgramTextDecoder(memory.lane(2));
  }

  public StatusCode decode(ProtocolFrame frame) {
    reset();
    if (frame == null || frame.isResponse()
        || frame.type() != ProtocolMessageType.PREPARE_PROGRAM
            && frame.type() != ProtocolMessageType.EXECUTE_PROGRAM
            && frame.type() != ProtocolMessageType.CLOSE_PROGRAM) {
      return failure(frame, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    StatusCode status;
    ByteBuffer source = frame.source();
    int input = frame.payloadOffset();
    int end = input + frame.payloadBytes();
    switch (frame.type()) {
      case PREPARE_PROGRAM -> status = ProtocolProgramGraphCodec.decode(source, input, end, program);
      case EXECUTE_PROGRAM -> status = decodeExecute(source, input, end);
      case CLOSE_PROGRAM -> status = decodeClose(source, input, end);
      default -> status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk()) return failure(frame, status);
    StatusCode erased = frame.erasePayload();
    return erased.isOk() ? StatusCode.OK : failure(null, erased);
  }

  public TransactionProgram program() { return program; }
  public TransactionProgramArguments arguments() { return arguments; }
  public long handle() { return handle; }

  public void reset() {
    handle = 0;
    program.reset();
    arguments.reset();
    text.reset();
  }

  public long retainedBytes() { return memory.retainedBytes(); }

  public StatusCode releaseHighWater() {
    return release();
  }

  public StatusCode release() {
    handle = 0;
    StatusCode programStatus = program.release();
    StatusCode argumentStatus = arguments.release();
    StatusCode textStatus = text.release();
    return !programStatus.isOk() ? programStatus
        : !argumentStatus.isOk() ? argumentStatus
        : textStatus;
  }

  private StatusCode decodeExecute(ByteBuffer source, int input, int end) {
    if (end - input < EXECUTE_HEADER_BYTES) return StatusCode.INVALID_EXTERNAL_INPUT;
    handle = source.getLong(input);
    int count = source.getInt(input + Long.BYTES);
    int reserved = source.getInt(input + Long.BYTES + Integer.BYTES);
    if (handle <= 0 || reserved != 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    return ProtocolParameterDecoder.decodeProgram(
        source, input + EXECUTE_HEADER_BYTES, end, count, arguments, text);
  }

  private StatusCode decodeClose(ByteBuffer source, int input, int end) {
    if (end - input != CLOSE_HEADER_BYTES) return StatusCode.INVALID_EXTERNAL_INPUT;
    handle = source.getLong(input);
    return handle > 0 ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode failure(ProtocolFrame frame, StatusCode status) {
    if (frame != null) frame.erasePayload();
    handle = 0;
    program.reset();
    arguments.reset();
    text.reset();
    return status;
  }
}
