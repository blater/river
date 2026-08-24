package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Decodes a validated response payload into a reusable carrier. */
final class ProtocolResponseDecoder {
  StatusCode decode(ByteBuffer source, ProtocolFrame frame, ProtocolResponse result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode status = ProtocolFrameWire.decode(source, frame, ProtocolFrameWire.ROLE_RESPONSE);
    if (!status.isOk()) return status;
    status = ProtocolResponsePayloadDecoder.decode(frame, result);
    if (!status.isOk()) {
      frame.reset();
      result.reset();
    }
    return status;
  }
}
