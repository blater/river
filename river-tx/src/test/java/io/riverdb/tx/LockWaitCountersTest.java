package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class LockWaitCountersTest {
  @Test
  void completeBlockedAcceptsZeroAndAccumulatesPositiveElapsedTime() {
    LockWaitCounters counters = new LockWaitCounters();

    counters.completeBlocked(100, 100);
    assertEquals(0, counters.blockedNanos());

    counters.completeBlocked(100, 117);
    assertEquals(17, counters.blockedNanos());

    counters.completeBlocked(200, 199);
    assertEquals(17, counters.blockedNanos());

    counters.completeBlocked(200, 205);
    assertEquals(22, counters.blockedNanos());
  }
}
