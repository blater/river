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

  StatusCode decodeAssembled(ByteBuffer source, ProtocolFrame frame, ProtocolResponse result) {
    if (source == null || frame == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    frame.reset();
    int payloadBytes = source.remaining() - ProtocolFrameCodec.HEADER_BYTES;
    ProtocolMessageType type = ProtocolMessageType.fromWireCode(source.getInt(8));
    if (payloadBytes <= ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
        || payloadBytes > ProtocolFrameCodec.MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES
        || type == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    frame.complete(source, type, source.getLong(16), ProtocolFrameCodec.HEADER_BYTES,
        payloadBytes, true);
    StatusCode status = ProtocolResponsePayloadDecoder.decode(frame, result);
    if (!status.isOk()) {
      frame.reset();
      result.reset();
    }
    return status;
  }
}
