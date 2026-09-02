package io.riverdb.base.key;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OrderedKeyTest {
  @Test
  void longMaximumSpaceIsFiniteAndSortsBelowTheExplicitInfinityFence() {
    assertTrue(OrderedKey.isFiniteSpace(Long.MAX_VALUE));
    assertTrue(OrderedKey.lessThan(
        Long.MAX_VALUE, Long.MAX_VALUE, OrderedKey.INFINITY_SPACE, 0));
    assertFalse(OrderedKey.isFiniteSpace(OrderedKey.INFINITY_SPACE));
    assertTrue(OrderedKey.isInfinity(OrderedKey.INFINITY_SPACE, 0));
    assertFalse(OrderedKey.isInfinity(OrderedKey.INFINITY_SPACE, 1));
  }

  @Test
  void finiteSpacesUseSignedLongOrderingWithoutCollidingWithInfinity() {
    assertTrue(OrderedKey.lessThan(0, Long.MAX_VALUE, 1, Long.MIN_VALUE));
    assertTrue(OrderedKey.lessThan(
        Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE));
    assertFalse(OrderedKey.equal(
        Long.MAX_VALUE, 0, OrderedKey.INFINITY_SPACE, 0));
  }
}
