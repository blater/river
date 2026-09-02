package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.tx.api.lock.LockDeadline;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import org.junit.jupiter.api.Test;

final class LockDeadlineTest {
  @Test
  void finiteDeadlineRemainsOrderedAcrossSignedLongWrap() {
    long now = Long.MAX_VALUE - 4;
    long deadline = LockDeadline.after(now, 10);

    assertEquals(Long.MIN_VALUE + 5, deadline);
    assertEquals(10, LockDeadline.remaining(deadline, now));
    assertEquals(1, LockDeadline.remaining(deadline, now + 9));
    assertTrue(LockDeadline.expired(deadline, deadline));
  }

  @Test
  void wrappedZeroIsAFiniteDeadlineRatherThanTheInfinitePolicy() {
    long now = -10;
    long deadline = LockDeadline.after(now, 10);
    LockRequest finite = new LockRequest()
        .setKey(1, 2, LockMode.EXCLUSIVE, 0)
        .waitUntil(deadline);
    LockRequest infinite = new LockRequest()
        .setKey(1, 2, LockMode.EXCLUSIVE, 0);

    assertEquals(0, deadline);
    assertTrue(finite.hasDeadline());
    assertEquals(10, LockDeadline.remaining(finite.deadlineNanos(), now));
    assertFalse(infinite.hasDeadline());
  }

  @Test
  void remainingDurationWorksFromTheNegativeNanoTimeHalf() {
    long now = Long.MIN_VALUE + 20;
    long deadline = LockDeadline.after(now, 50);

    assertEquals(50, LockDeadline.remaining(deadline, now));
    assertFalse(LockDeadline.expired(deadline, now));
    assertEquals(0, LockDeadline.remaining(deadline, deadline + 1));
  }
}
