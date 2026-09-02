package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;

/** Canonical direction and continuation flags for one physical frame. */
final class ProtocolFrameFlags {
  private ProtocolFrameFlags() { }

  static StatusCode validate(ProtocolMessageType type, int flags, int role) {
    int valid = ProtocolFrameWire.FRAME_RESPONSE
        | ProtocolFrameWire.FRAME_CONTINUATION | ProtocolFrameWire.FRAME_FINAL;
    boolean response = (flags & ProtocolFrameWire.FRAME_RESPONSE) != 0;
    boolean continuation = (flags & ProtocolFrameWire.FRAME_CONTINUATION) != 0;
    boolean last = (flags & ProtocolFrameWire.FRAME_FINAL) != 0;
    if ((flags & ~valid) != 0 || role == ProtocolFrameWire.ROLE_REQUEST && response
        || role == ProtocolFrameWire.ROLE_RESPONSE && !response
        || last && !continuation
        || continuation && role == ProtocolFrameWire.ROLE_REQUEST && !type.requiresPayload()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }
}
