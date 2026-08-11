package io.riverdb.protocol;

import java.nio.ByteBuffer;

/** Caller-owned decoded frame view, valid while its source buffer is unchanged. */
public final class ProtocolFrame {
  private ByteBuffer source;
  private ProtocolMessageType type;
  private long requestId;
  private int payloadOffset;
  private int payloadBytes;
  private boolean response;

  public void reset() {
    source = null;
    type = null;
    requestId = 0;
    payloadOffset = 0;
    payloadBytes = 0;
    response = false;
  }

  void complete(
      ByteBuffer frameSource,
      ProtocolMessageType messageType,
      long id,
      int offset,
      int length,
      boolean isResponse) {
    source = frameSource;
    type = messageType;
    requestId = id;
    payloadOffset = offset;
    payloadBytes = length;
    response = isResponse;
  }

  byte payloadByteAt(int index) {
    return source.get(payloadOffset + index);
  }

  int payloadOffset() {
    return payloadOffset;
  }

  ByteBuffer source() {
    return source;
  }

  public ProtocolMessageType type() {
    return type;
  }

  public long requestId() {
    return requestId;
  }

  public int payloadBytes() {
    return payloadBytes;
  }

  public boolean isResponse() {
    return response;
  }
}
