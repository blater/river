package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolFrameHeader;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.protocol.ProtocolRequestAssembly;
import java.nio.ByteBuffer;

/** Admits either one physical request or one complete continuation sequence. */
final class ServerRequestAssembly {
  private final ProtocolRequestAssembly continuation;
  private ByteBuffer admitted;

  ServerRequestAssembly(RetainedMemoryLease memory) {
    continuation = new ProtocolRequestAssembly(memory);
  }

  StatusCode accept(ProtocolFrameCodec codec, ByteBuffer request, ProtocolFrameHeader header) {
    admitted = null;
    if (!header.isContinuation()) {
      if (continuation.isActive()) return StatusCode.INVALID_EXTERNAL_INPUT;
      admitted = request;
      return StatusCode.OK;
    }
    StatusCode status = continuation.accept(request, header);
    codec.eraseRequestPayload(request);
    if (status.isOk() && continuation.isComplete()) admitted = continuation.source();
    return status;
  }

  ByteBuffer admitted() { return admitted; }
  boolean isActive() { return continuation.isActive(); }
  void reset() { continuation.reset(); admitted = null; }
  void release() { continuation.release(); admitted = null; }
}
