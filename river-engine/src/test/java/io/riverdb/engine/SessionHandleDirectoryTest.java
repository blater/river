package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.sql.SqlRetainedBudget;
import org.junit.jupiter.api.Test;

final class SessionHandleDirectoryTest {
  @Test
  void rejectsForeignAndStaleHandlesWithoutAConfiguredCountLimit() {
    TrackingBudget budget = new TrackingBudget();
    SessionHandleDirectory first = new SessionHandleDirectory(budget);
    SessionHandleDirectory second = new SessionHandleDirectory(budget);
    long firstHandle = first.add(11);
    long secondHandle = second.add(11);
    assertTrue(firstHandle > 0);
    assertTrue(secondHandle > 0);
    assertNotEquals(firstHandle, secondHandle);
    assertEquals(11, first.resolve(firstHandle));
    assertEquals(0, first.resolve(secondHandle));
    assertEquals(0, second.resolve(firstHandle));
    assertTrue(first.remove(firstHandle));
    assertEquals(0, first.resolve(firstHandle));

    for (int resource = 1; resource <= 10_000; resource++) {
      assertTrue(first.add(resource) > 0);
    }
    assertEquals(StatusCode.OK, first.clear());
    assertEquals(StatusCode.OK, second.clear());
    assertEquals(0, budget.bytes);
  }

  @Test
  void compactsDeletedHandlesWithoutRequestingAZeroByteReservation() {
    TrackingBudget budget = new TrackingBudget();
    SessionHandleDirectory directory = new SessionHandleDirectory(budget);
    long[] handles = new long[21];
    for (int index = 0; index < handles.length; index++) {
      handles[index] = directory.add(index + 1);
      assertTrue(handles[index] > 0);
    }
    long retained = budget.bytes;
    for (long handle : handles) assertTrue(directory.remove(handle));

    long replacement = directory.add(91);

    assertTrue(replacement > 0);
    assertEquals(91, directory.resolve(replacement));
    assertEquals(retained, budget.bytes);
    assertEquals(StatusCode.OK, directory.clear());
    assertEquals(0, budget.bytes);
  }

  private static final class TrackingBudget implements SqlRetainedBudget {
    private long bytes;

    @Override
    public StatusCode reserveRetainedBytes(long amount) {
      if (amount <= 0 || bytes > Long.MAX_VALUE - amount) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      bytes += amount;
      return StatusCode.OK;
    }

    @Override
    public StatusCode releaseRetainedBytes(long amount) {
      if (amount < 0 || amount > bytes) return StatusCode.INVARIANT_BROKEN;
      bytes -= amount;
      return StatusCode.OK;
    }
  }
}
