package io.riverdb.protocol;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RetainedMemoryLease;
import java.nio.ByteBuffer;

/** Reusable request assembly that exposes no partial logical frame. */
public final class ProtocolRequestAssembly {
  private static final byte[] EMPTY = new byte[0];
  private final RetainedMemoryLease memory;
  private final ByteBuffer emptyBuffer = ByteBuffer.wrap(EMPTY);
  private byte[] bytes = EMPTY;
  private ByteBuffer buffer = emptyBuffer;
  private int total;
  private int next;
  private ProtocolMessageType type;
  private long requestId;
  private boolean complete;

  public ProtocolRequestAssembly(RetainedMemoryLease retainedMemory) {
    if (retainedMemory == null) throw new IllegalArgumentException("retainedMemory");
    memory = retainedMemory;
  }

  public void reset() { total = 0; next = 0; type = null; requestId = 0; complete = false; }

  public void release() {
    for (int index = 0; index < bytes.length; index++) bytes[index] = 0;
    bytes = EMPTY;
    buffer = emptyBuffer;
    reset();
    if (!memory.resize(0).isOk()) throw new IllegalStateException("memory lease release");
  }

  public StatusCode accept(ByteBuffer source, ProtocolFrameHeader header) {
    int payload = source == null ? 0 : source.position() + ProtocolFrameCodec.HEADER_BYTES;
    if (source == null || header == null || !header.isContinuation() || header.isResponse()
        || source.remaining() != ProtocolFrameCodec.HEADER_BYTES + header.payloadBytes()
        || header.payloadBytes() < ProtocolResponseSegmenter.SEGMENT_BYTES) return invalid();
    int declared = source.getInt(payload);
    int offset = source.getInt(payload + 4);
    int length = source.getInt(payload + 8);
    if (length != header.payloadBytes() - ProtocolResponseSegmenter.SEGMENT_BYTES
        || declared <= ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
        || declared > ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES
        || offset != next || offset < 0 || length <= 0 || offset > declared - length
        || header.isFinalSegment() != (offset + length == declared)) return invalid();
    if (offset == 0) {
      ProtocolMessageType firstType = ProtocolMessageType.fromWireCode(header.typeWireCode());
      if (firstType == null || !firstType.requiresPayload() || header.requestId() <= 0) {
        return invalid();
      }
      StatusCode status = reserve(declared + ProtocolFrameCodec.HEADER_BYTES);
      if (!status.isOk()) {
        reset();
        return status;
      }
      total = declared;
      type = firstType;
      requestId = header.requestId();
    } else if (declared != total || header.typeWireCode() != type.wireCode()
        || header.requestId() != requestId) return invalid();
    for (int index = 0; index < length; index++) {
      bytes[ProtocolFrameCodec.HEADER_BYTES + offset + index] = source.get(
          payload + ProtocolResponseSegmenter.SEGMENT_BYTES + index);
    }
    next += length;
    complete = header.isFinalSegment();
    return StatusCode.OK;
  }

  public boolean isComplete() { return complete; }
  public boolean isActive() { return next > 0 && !complete; }
  public ByteBuffer source() {
    if (!complete) return null;
    ProtocolFrameWire.begin(buffer, type, requestId, total, 0);
    return buffer;
  }

  private StatusCode reserve(int required) {
    if (required <= bytes.length) return StatusCode.OK;
    int maximum = ProtocolFrameCodec.HEADER_BYTES
        + ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES;
    int capacity = BoundedArrayGrowth.capacity(bytes.length, required, maximum, 16 * 1024);
    StatusCode admitted = memory.resize(capacity);
    if (!admitted.isOk()) return admitted;
    try {
      byte[] grown = new byte[capacity];
      ByteBuffer view = ByteBuffer.wrap(grown);
      bytes = grown;
      buffer = view;
      return StatusCode.OK;
    }
    catch (OutOfMemoryError failure) {
      memory.resize(bytes.length);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode invalid() {
    release();
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
