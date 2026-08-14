package io.riverdb.protocol;

/**
 * Reusable validated frame-header metadata. Values are available until the
 * carrier is reused by another inspection.
 */
public final class ProtocolFrameHeader {
  private int typeWireCode;
  private long requestId;
  private int payloadBytes;
  private boolean response;
  private boolean available;

  public int typeWireCode() {
    return available ? typeWireCode : 0;
  }

  public long requestId() {
    return available ? requestId : 0;
  }

  public int payloadBytes() {
    return available ? payloadBytes : 0;
  }

  public boolean isResponse() {
    return available && response;
  }

  public boolean isAvailable() {
    return available;
  }

  void complete(
      int inspectedTypeWireCode,
      long inspectedRequestId,
      int inspectedPayloadBytes,
      boolean inspectedResponse) {
    typeWireCode = inspectedTypeWireCode;
    requestId = inspectedRequestId;
    payloadBytes = inspectedPayloadBytes;
    response = inspectedResponse;
    available = true;
  }

  void reset() {
    typeWireCode = 0;
    requestId = 0;
    payloadBytes = 0;
    response = false;
    available = false;
  }
}
