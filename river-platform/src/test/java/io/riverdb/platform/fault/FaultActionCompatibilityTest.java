package io.riverdb.platform.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class FaultActionCompatibilityTest {
  @Test
  void coversEveryActionOperationPair() {
    int checkedPairs = 0;
    for (FaultAction action : FaultAction.values()) {
      for (FaultOperation operation : FaultOperation.values()) {
        assertEquals(
            expected(action, operation),
            action.isCompatibleWith(operation),
            action + " with " + operation);
        checkedPairs++;
      }
    }
    assertEquals(
        FaultAction.values().length * FaultOperation.values().length,
        checkedPairs);
  }

  @Test
  void nullOperationAlwaysFailsClosed() {
    for (FaultAction action : FaultAction.values()) {
      assertEquals(false, action.isCompatibleWith(null));
    }
  }

  private static boolean expected(FaultAction action, FaultOperation operation) {
    return switch (action) {
      case NONE, CRASH, CANCEL -> true;
      case RESTART -> operation != FaultOperation.CRASH
          && operation != FaultOperation.SCHEDULE
          && operation != FaultOperation.RUN_TASK;
      case SHORT_READ, CORRUPT_READ, DETECTED_CORRUPTION ->
          operation == FaultOperation.READ;
      case SHORT_WRITE, PARTIAL_WRITE, TORN_WRITE -> operation == FaultOperation.WRITE;
      case FORCE_FAILURE -> operation == FaultOperation.FORCE;
      case DISK_FULL -> operation == FaultOperation.WRITE || operation == FaultOperation.FORCE;
    };
  }
}
