package io.riverdb.testkit.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class ManualMonotonicClockTest {
  @Test
  void neverMovesBackwardsOrWraps() {
    ManualMonotonicClock clock = new ManualMonotonicClock(10);

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, clock.advanceTo(9));
    assertEquals(10, clock.nanoTime());
    assertEquals(StatusCode.OK, clock.advanceBy(5));
    assertEquals(15, clock.nanoTime());

    ManualMonotonicClock nearLimit = new ManualMonotonicClock(Long.MAX_VALUE - 1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, nearLimit.advanceBy(2));
    assertEquals(Long.MAX_VALUE - 1, nearLimit.nanoTime());
  }
}
