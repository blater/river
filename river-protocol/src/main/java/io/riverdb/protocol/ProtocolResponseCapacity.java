package io.riverdb.protocol;

/** Capacity selection for retained logical responses within protocol addressability. */
public final class ProtocolResponseCapacity {
  private ProtocolResponseCapacity() {
  }

  /** One growth step; -1 rejects requests outside the logical response bound. */
  public static int select(int current, int required) {
    if (required < 0 || required > ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES) return -1;
    if (required <= current) return current;
    int doubled = current <= ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES / 2
        ? current * 2 : ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES;
    return Math.max(required, doubled);
  }
}
