package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ProtocolResponseCapacityTest {
  @Test
  void supportsExactClientRequestsAndIncrementalServerGrowthAtTheWireLimit() {
    int initial = ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
    int maximum = ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES;
    assertEquals(initial, ProtocolResponseCapacity.select(initial, initial));
    assertEquals(initial * 2, ProtocolResponseCapacity.select(initial, initial + 1));
    assertEquals(initial * 3, ProtocolResponseCapacity.select(initial, initial * 3));
    assertEquals(maximum, ProtocolResponseCapacity.select(maximum - 1, maximum));
    assertEquals(maximum, ProtocolResponseCapacity.select(maximum, 0));
    assertEquals(-1, ProtocolResponseCapacity.select(maximum, maximum + 1));
    assertEquals(-1, ProtocolResponseCapacity.select(initial, -1));
    assertEquals(-1, ProtocolResponseCapacity.select(initial, Integer.MAX_VALUE));
  }
}
