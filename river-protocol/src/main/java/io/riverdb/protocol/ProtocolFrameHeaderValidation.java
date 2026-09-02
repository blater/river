package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;

/** Validates decoded frame-header fields without retaining external storage. */
final class ProtocolFrameHeaderValidation {
  private ProtocolFrameHeaderValidation() { }

  static StatusCode validate(ProtocolMessageType type, int flags, long requestId,
      int payloadBytes, int reserved, int role) {
    if (type == null || requestId <= 0 || payloadBytes < 0 || reserved != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = ProtocolFrameFlags.validate(type, flags, role);
    return status.isOk()
        ? ProtocolFramePayloadValidation.validate(type, flags, payloadBytes, role) : status;
  }
}
