package io.riverdb.protocol;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Reusable gap-free continuation assembly; contents publish only after the final segment. */
public final class ProtocolResponseAssembly {
  private byte[] bytes = new byte[0];
  private ByteBuffer buffer = ByteBuffer.wrap(bytes);
  private int totalBytes;
  private int nextOffset;
  private ProtocolMessageType type;
  private long requestId;
  private boolean complete;

  public void reset() {
    totalBytes = 0;
    nextOffset = 0;
    type = null;
    requestId = 0;
    complete = false;
  }

  StatusCode accept(ByteBuffer source, ProtocolFrameHeader header) {
    int payload = source == null ? 0 : source.position() + ProtocolFrameCodec.HEADER_BYTES;
    if (source == null || header == null || !header.isContinuation()
        || !header.isResponse()
        || source.remaining() != ProtocolFrameCodec.HEADER_BYTES + header.payloadBytes()
        || header.payloadBytes() < ProtocolResponseSegmenter.SEGMENT_BYTES) {
      return invalid();
    }
    int total = source.getInt(payload);
    int offset = source.getInt(payload + 4);
    int length = source.getInt(payload + 8);
    if (length != header.payloadBytes() - ProtocolResponseSegmenter.SEGMENT_BYTES
        || total <= ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
        || total > ProtocolFrameCodec.MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES
        || offset != nextOffset || offset < 0 || length <= 0 || offset > total - length
        || header.isFinalSegment() != (offset + length == total)) {
      return invalid();
    }
    if (offset == 0) {
      ProtocolMessageType firstType = ProtocolMessageType.fromWireCode(header.typeWireCode());
      if (firstType == null || header.requestId() <= 0) return invalid();
      StatusCode status = reserve(total + ProtocolFrameCodec.HEADER_BYTES);
      if (!status.isOk()) {
        reset();
        return status;
      }
      totalBytes = total;
      type = firstType;
      requestId = header.requestId();
    } else if (total != totalBytes || header.typeWireCode() != type.wireCode()
        || header.requestId() != requestId) {
      return invalid();
    }
    for (int index = 0; index < length; index++) {
      bytes[ProtocolFrameCodec.HEADER_BYTES + offset + index] =
          source.get(payload + ProtocolResponseSegmenter.SEGMENT_BYTES + index);
    }
    nextOffset += length;
    complete = header.isFinalSegment();
    return StatusCode.OK;
  }

  boolean isComplete() { return complete; }
  boolean isActive() { return nextOffset > 0 && !complete; }

  ByteBuffer source() {
    if (!complete) return null;
    ProtocolFrameWire.begin(buffer, type, requestId, totalBytes, ProtocolFrameWire.FRAME_RESPONSE);
    return buffer;
  }

  private StatusCode reserve(int required) {
    if (required <= bytes.length) return StatusCode.OK;
    int maximum = ProtocolFrameCodec.HEADER_BYTES
        + ProtocolFrameCodec.MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES;
    int capacity = BoundedArrayGrowth.capacity(bytes.length, required, maximum, 16 * 1024);
    try {
      byte[] grown = new byte[capacity];
      ByteBuffer view = ByteBuffer.wrap(grown);
      bytes = grown;
      buffer = view;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode invalid() {
    reset();
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
