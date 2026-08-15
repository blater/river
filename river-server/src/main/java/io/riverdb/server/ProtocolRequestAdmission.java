package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.protocol.ProtocolFrame;
import io.riverdb.protocol.ProtocolFrameCodec;
import java.nio.ByteBuffer;

/** Validates caller buffers and wipes rejected request payloads at admission. */
final class ProtocolRequestAdmission {
  private ProtocolRequestAdmission() {
  }

  static StatusCode validate(
      ProtocolFrameCodec codec, ByteBuffer request, ByteBuffer response) {
    if (request == null || request.isReadOnly()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (response == null || request == response || response.isReadOnly()) {
      codec.eraseRequestPayload(request);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (response.capacity() < ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES) {
      codec.eraseRequestPayload(request);
      empty(response);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    empty(response);
    return StatusCode.OK;
  }

  static StatusCode decode(
      ProtocolFrameCodec codec, ByteBuffer request, ProtocolFrame frame) {
    StatusCode status = codec.decode(request, frame);
    if (status.isOk()) return status;
    StatusCode erased = codec.eraseRequestPayload(request);
    return erased.isOk() ? status : erased;
  }

  private static void empty(ByteBuffer response) {
    response.clear();
    response.limit(0);
  }
}
