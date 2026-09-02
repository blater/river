package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Publishes one internally assembled logical request as a frame. */
final class ProtocolAssembledRequestDecoder {
  private ProtocolAssembledRequestDecoder() { }

  static StatusCode decode(ByteBuffer source, ProtocolFrame result) {
    if (source == null || result == null || source.position() != 0
        || source.remaining() <= ProtocolFrameCodec.MAXIMUM_FRAME_BYTES
        || source.remaining() > ProtocolFrameCodec.HEADER_BYTES
            + ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    ProtocolMessageType type = ProtocolMessageType.fromWireCode(source.getInt(8));
    int payload = source.getInt(24);
    if (type == null || !type.requiresPayload() || source.getLong(16) <= 0
        || payload != source.remaining() - ProtocolFrameCodec.HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.complete(source, type, source.getLong(16), ProtocolFrameCodec.HEADER_BYTES,
        payload, false);
    return StatusCode.OK;
  }
}
