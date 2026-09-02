package io.riverdb.base.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class BoundedArrayGrowthTest {
  @Test
  void growsGeometricallyWithoutCrossingTheMaximum() {
    assertEquals(8, BoundedArrayGrowth.capacity(0, 1, 1_664, 8));
    assertEquals(8, BoundedArrayGrowth.capacity(8, 8, 1_664, 8));
    assertEquals(16, BoundedArrayGrowth.capacity(8, 9, 1_664, 8));
    assertEquals(1_664, BoundedArrayGrowth.capacity(1_024, 1_665 - 1, 1_664, 8));
    assertEquals(3, BoundedArrayGrowth.capacity(0, 3, 3, 8));
  }

  @Test
  void rejectsInvalidOrUnboundedRequests() {
    assertEquals(-1, BoundedArrayGrowth.capacity(-1, 0, 0, 8));
    assertEquals(-1, BoundedArrayGrowth.capacity(0, -1, 0, 8));
    assertEquals(-1, BoundedArrayGrowth.capacity(0, 1, 0, 8));
    assertEquals(-1, BoundedArrayGrowth.capacity(0, 0, -1, 8));
    assertEquals(-1, BoundedArrayGrowth.capacity(0, 0, 0, 0));
  }
}
