package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class IndexedLogicalRowIdFloorsTest {
  @Test
  void coalescesMaximumFloorByTableInFirstSeenOrder() {
    IndexedLogicalRowIdFloors floors = new IndexedLogicalRowIdFloors(4);

    assertEquals(StatusCode.OK, floors.record(17, 8));
    assertEquals(StatusCode.OK, floors.record(23, 4));
    assertEquals(StatusCode.OK, floors.record(17, 6));
    assertEquals(StatusCode.OK, floors.record(17, 12));

    assertEquals(2, floors.count());
    assertEquals(17, floors.objectIdAt(0));
    assertEquals(12, floors.nextAt(0));
    assertEquals(23, floors.objectIdAt(1));
    assertEquals(4, floors.nextAt(1));
  }

  @Test
  void capacityComesOnlyFromAdmittedMutationBudget() {
    IndexedLogicalRowIdFloors floors = new IndexedLogicalRowIdFloors(2);

    assertEquals(StatusCode.OK, floors.record(1, 2));
    assertEquals(StatusCode.OK, floors.record(2, 2));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, floors.record(3, 2));
    assertEquals(StatusCode.OK, floors.record(2, 9));
    assertEquals(2, floors.count());
    assertEquals(9, floors.nextAt(1));
  }

  @Test
  void resetRetainsCapacityAndReleaseDropsJournal() {
    IndexedLogicalRowIdFloors floors = new IndexedLogicalRowIdFloors(2);
    assertEquals(StatusCode.OK, floors.record(5, 10));
    assertEquals(StatusCode.OK, floors.record(6, 11));

    floors.reset();
    assertEquals(0, floors.count());
    assertEquals(StatusCode.OK, floors.record(7, 20));
    assertEquals(7, floors.objectIdAt(0));
    assertEquals(20, floors.nextAt(0));

    floors.release();
    assertEquals(0, floors.count());
  }

  @Test
  void reportsExactGrowthBeforeRecordingANewFloor() {
    IndexedLogicalRowIdFloors floors = new IndexedLogicalRowIdFloors(4);

    long firstReservation = floors.accountedBytesForRecord(17);
    assertEquals(StatusCode.OK, floors.record(17, 8));
    assertEquals(firstReservation, floors.accountedBytes());
    assertEquals(firstReservation, floors.accountedBytesForRecord(17));

    long secondReservation = floors.accountedBytesForRecord(23);
    assertEquals(StatusCode.OK, floors.record(23, 4));
    assertEquals(secondReservation, floors.accountedBytes());
  }

  @Test
  void relationalCompilationReservesFloorStorageBeforeAppend() {
    IndexedRelationalMutationBuffer mutation =
        new IndexedRelationalMutationBuffer(2, 0, 0);
    long reservation = mutation.accountedBytesForReservation(0, 0, 0, 0, 2);

    assertEquals(StatusCode.OK, mutation.reserve(0, 0, 0, 0, 2));
    assertEquals(reservation, mutation.accountedBytes());
    assertEquals(StatusCode.OK, mutation.appendLogicalRowFloor(17, 8));
    assertEquals(StatusCode.OK, mutation.appendLogicalRowFloor(23, 4));
    assertEquals(StatusCode.OK, mutation.seal());
  }
}
