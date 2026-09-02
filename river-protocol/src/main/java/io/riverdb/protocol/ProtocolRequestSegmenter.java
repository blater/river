package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Expands one logical SQL request into bounded physical frames in place. */
final class ProtocolRequestSegmenter {
  private static final int MAGIC = 0x52495652;
  private ProtocolRequestSegmenter() { }

  static StatusCode prepare(ByteBuffer target, int payloadBytes) {
    if (payloadBytes > ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES) {
      ProtocolFrameWire.empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int required = payloadBytes <= ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
        ? ProtocolFrameCodec.HEADER_BYTES + payloadBytes
        : ProtocolFrameCodec.continuedRequestWireBytes(payloadBytes);
    return target.capacity() >= required
        ? StatusCode.OK : ProtocolFrameWire.exhaustedTarget(target);
  }

  static StatusCode finish(ByteBuffer target, ProtocolMessageType type,
      long requestId, int payloadBytes) {
    if (payloadBytes <= ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
      target.position(0);
      target.limit(ProtocolFrameCodec.HEADER_BYTES + payloadBytes);
      return StatusCode.OK;
    }
    int dataLimit = ProtocolResponseSegmenter.DATA_BYTES;
    int frames = (payloadBytes + dataLimit - 1) / dataLimit;
    int wireBytes = ProtocolFrameCodec.continuedRequestWireBytes(payloadBytes);
    if (wireBytes == 0 || wireBytes > target.capacity()) {
      return ProtocolFrameWire.exhaustedTarget(target);
    }
    target.limit(target.capacity());
    for (int frame = frames - 1; frame >= 0; frame--) {
      int source = frame * dataLimit;
      int length = Math.min(dataLimit, payloadBytes - source);
      int start = frame * (ProtocolFrameCodec.HEADER_BYTES
          + ProtocolResponseSegmenter.SEGMENT_BYTES + dataLimit);
      for (int index = length - 1; index >= 0; index--) {
        target.put(start + ProtocolFrameCodec.HEADER_BYTES
            + ProtocolResponseSegmenter.SEGMENT_BYTES + index,
            target.get(ProtocolFrameCodec.HEADER_BYTES + source + index));
      }
      writeHeader(target, start, type, requestId,
          length + ProtocolResponseSegmenter.SEGMENT_BYTES, frame == frames - 1);
      target.putInt(start + ProtocolFrameCodec.HEADER_BYTES, payloadBytes);
      target.putInt(start + ProtocolFrameCodec.HEADER_BYTES + 4, source);
      target.putInt(start + ProtocolFrameCodec.HEADER_BYTES + 8, length);
    }
    target.position(0);
    target.limit(wireBytes);
    return StatusCode.OK;
  }

  private static void writeHeader(ByteBuffer target, int start, ProtocolMessageType type,
      long requestId, int payloadBytes, boolean last) {
    target.order(ByteOrder.BIG_ENDIAN);
    target.putInt(start, MAGIC);
    target.putInt(start + 4, ProtocolFrameCodec.VERSION);
    target.putInt(start + 8, type.wireCode());
    target.putInt(start + 12, ProtocolFrameWire.FRAME_CONTINUATION
        | (last ? ProtocolFrameWire.FRAME_FINAL : 0));
    target.putLong(start + 16, requestId);
    target.putInt(start + 24, payloadBytes);
    target.putInt(start + 28, 0);
  }
}
