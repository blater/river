package io.riverdb.platform.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class FaultActionCompatibilityTest {
  @Test
  void coversEveryActionOperationPair() {
    int checkedPairs = 0;
    for (FaultAction action : FaultAction.values()) {
      for (FaultOperation operation : FaultOperation.values()) {
        for (FaultBoundary boundary : FaultBoundary.values()) {
          assertEquals(
              expected(action, operation, boundary),
              action.isCompatibleWith(operation, boundary),
              action + " with " + operation + " at " + boundary);
          checkedPairs++;
        }
      }
    }
    assertEquals(
        FaultAction.values().length
            * FaultOperation.values().length
            * FaultBoundary.values().length,
        checkedPairs);
  }

  @Test
  void nullOperationAlwaysFailsClosed() {
    for (FaultAction action : FaultAction.values()) {
      assertEquals(false, action.isCompatibleWith(null));
      for (FaultOperation operation : FaultOperation.values()) {
        assertEquals(false, action.isCompatibleWith(operation, null));
      }
    }
  }

  private static boolean expected(
      FaultAction action,
      FaultOperation operation,
      FaultBoundary boundary) {
    if (boundary == FaultBoundary.AFTER) {
      return switch (action) {
        case NONE, CRASH, CANCEL -> true;
        case RESTART -> operation != FaultOperation.CRASH
            && operation != FaultOperation.SCHEDULE
            && operation != FaultOperation.RUN_TASK;
        case DELAY -> installOperation(operation);
        case SHORT_READ, SHORT_WRITE, PARTIAL_WRITE, FORCE_FAILURE, DISK_FULL,
            TORN_WRITE, CORRUPT_READ, DETECTED_CORRUPTION -> false;
      };
    }
    return switch (action) {
      case NONE, CRASH, CANCEL -> true;
      case DELAY -> operation == FaultOperation.TEMP_CREATE
          || operation == FaultOperation.TEMP_WRITE
          || operation == FaultOperation.TEMP_FORCE
          || operation == FaultOperation.REPLACE
          || operation == FaultOperation.DIRECTORY_FORCE
          || operation == FaultOperation.REOPEN_VERIFY;
      case RESTART -> operation != FaultOperation.CRASH
          && operation != FaultOperation.SCHEDULE
          && operation != FaultOperation.RUN_TASK;
      case SHORT_READ, CORRUPT_READ, DETECTED_CORRUPTION ->
          operation == FaultOperation.READ || operation == FaultOperation.REOPEN_VERIFY;
      case SHORT_WRITE, PARTIAL_WRITE, TORN_WRITE ->
          operation == FaultOperation.WRITE || operation == FaultOperation.TEMP_WRITE;
      case FORCE_FAILURE -> operation == FaultOperation.FORCE
          || operation == FaultOperation.TEMP_FORCE
          || operation == FaultOperation.DIRECTORY_FORCE;
      case DISK_FULL -> operation == FaultOperation.WRITE
          || operation == FaultOperation.FORCE
          || operation == FaultOperation.TEMP_CREATE
          || operation == FaultOperation.TEMP_WRITE
          || operation == FaultOperation.TEMP_FORCE
          || operation == FaultOperation.DIRECTORY_FORCE;
    };
  }

  private static boolean installOperation(FaultOperation operation) {
    return operation == FaultOperation.TEMP_CREATE
        || operation == FaultOperation.TEMP_WRITE
        || operation == FaultOperation.TEMP_FORCE
        || operation == FaultOperation.REPLACE
        || operation == FaultOperation.DIRECTORY_FORCE
        || operation == FaultOperation.REOPEN_VERIFY;
  }
}
