package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Expands one logical response payload into bounded physical frames in place. */
final class ProtocolResponseSegmenter {
  static final int SEGMENT_BYTES = 12;
  static final int DATA_BYTES = ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES - SEGMENT_BYTES;
  private static final int MAGIC = 0x52495652;

  private ProtocolResponseSegmenter() { }

  static StatusCode finish(ByteBuffer target, ProtocolMessageType type,
      long requestId, int payloadBytes) {
    if (payloadBytes <= ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
      target.position(0);
      target.limit(ProtocolFrameCodec.HEADER_BYTES + payloadBytes);
      return StatusCode.OK;
    }
    int frames = (payloadBytes + DATA_BYTES - 1) / DATA_BYTES;
    int wireBytes = payloadBytes + frames * (ProtocolFrameCodec.HEADER_BYTES + SEGMENT_BYTES);
    if (wireBytes > target.capacity()) return ProtocolFrameWire.exhaustedTarget(target);
    target.limit(target.capacity());
    for (int frame = frames - 1; frame >= 0; frame--) {
      int sourceOffset = frame * DATA_BYTES;
      int dataBytes = Math.min(DATA_BYTES, payloadBytes - sourceOffset);
      int frameOffset = frame * (ProtocolFrameCodec.HEADER_BYTES + SEGMENT_BYTES + DATA_BYTES);
      for (int index = dataBytes - 1; index >= 0; index--) {
        target.put(frameOffset + ProtocolFrameCodec.HEADER_BYTES + SEGMENT_BYTES + index,
            target.get(ProtocolFrameCodec.HEADER_BYTES + sourceOffset + index));
      }
      writeHeader(target, frameOffset, type, requestId, dataBytes + SEGMENT_BYTES,
          frame == frames - 1);
      target.putInt(frameOffset + ProtocolFrameCodec.HEADER_BYTES, payloadBytes);
      target.putInt(frameOffset + ProtocolFrameCodec.HEADER_BYTES + 4, sourceOffset);
      target.putInt(frameOffset + ProtocolFrameCodec.HEADER_BYTES + 8, dataBytes);
    }
    target.position(0);
    target.limit(wireBytes);
    return StatusCode.OK;
  }

  private static void writeHeader(ByteBuffer target, int offset, ProtocolMessageType type,
      long requestId, int payloadBytes, boolean last) {
    target.order(ByteOrder.BIG_ENDIAN);
    target.putInt(offset, MAGIC);
    target.putInt(offset + 4, ProtocolFrameCodec.VERSION);
    target.putInt(offset + 8, type.wireCode());
    target.putInt(offset + 12, ProtocolFrameWire.FRAME_RESPONSE
        | ProtocolFrameWire.FRAME_CONTINUATION | (last ? ProtocolFrameWire.FRAME_FINAL : 0));
    target.putLong(offset + 16, requestId);
    target.putInt(offset + 24, payloadBytes);
    target.putInt(offset + 28, 0);
  }
}
