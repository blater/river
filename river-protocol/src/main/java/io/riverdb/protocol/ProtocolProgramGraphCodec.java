package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgram;
import java.nio.ByteBuffer;

/** Shared dispatch for the canonical PREPARE_PROGRAM graph wire format. */
final class ProtocolProgramGraphCodec {
  static final int FORMAT = 1;
  static final int HEADER_BYTES = 32;
  static final int STEP_BYTES = 56;
  static final int EXPRESSION_BYTES = 8;
  static final int NODE_BYTES = 16;

  private ProtocolProgramGraphCodec() { }

  static StatusCode encode(ByteBuffer target, long requestId, TransactionProgram program) {
    return ProtocolProgramGraphEncoder.encode(target, requestId, program);
  }

  static StatusCode decode(ByteBuffer source, int offset, int end, TransactionProgram program) {
    return ProtocolProgramGraphDecoder.decode(source, offset, end, program);
  }
}
