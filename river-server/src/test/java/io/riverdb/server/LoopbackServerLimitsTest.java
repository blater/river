package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LoopbackServerLimitsTest {
  @Test
  void acceptsExplicitCapacitiesBeyondFormerConvenienceLimits() {
    assertTrue(new LoopbackServerLimits(1_025, 300_001, 300_001, 1_000_001).isValid());
  }

  @Test
  void rejectsNonPositiveCapacitiesAndTimeouts() {
    assertFalse(new LoopbackServerLimits(0, 1, 1, 1).isValid());
    assertFalse(new LoopbackServerLimits(1, 0, 1, 1).isValid());
    assertFalse(new LoopbackServerLimits(1, 1, 0, 1).isValid());
    assertFalse(new LoopbackServerLimits(1, 1, 1, 0).isValid());
  }
}
