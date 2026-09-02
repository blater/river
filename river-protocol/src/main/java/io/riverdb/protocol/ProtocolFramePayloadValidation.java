package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;

/** Enforces physical-frame payload bounds after flag validation. */
final class ProtocolFramePayloadValidation {
  private ProtocolFramePayloadValidation() { }

  static StatusCode validate(
      ProtocolMessageType type, int flags, int payloadBytes, int role) {
    if (payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean continuation = (flags & ProtocolFrameWire.FRAME_CONTINUATION) != 0;
    if (role == ProtocolFrameWire.ROLE_REQUEST && !continuation
        && type.requiresPayload() != (payloadBytes > 0)
        || role == ProtocolFrameWire.ROLE_RESPONSE && !continuation && payloadBytes < 64
        || role == ProtocolFrameWire.ROLE_RESPONSE && continuation
            && payloadBytes < ProtocolResponseSegmenter.SEGMENT_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }
}
