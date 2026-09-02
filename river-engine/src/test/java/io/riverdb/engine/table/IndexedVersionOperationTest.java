package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabaseResourceDefaults;
import org.junit.jupiter.api.Test;

final class IndexedVersionOperationTest {
  @Test
  void reservesStructuralVersionsBeyondRowCountAndReusesCapacity() {
    IndexedVersionOperation operation = new IndexedVersionOperation();
    assertEquals(1_025, IndexedVersionOperation.required(1_024, 1));
    assertEquals(StatusCode.OK, operation.reserve(1_025));
    stage(operation, 1_025);
    long retainedBytes = operation.accountedBytes();

    operation.begin();
    assertEquals(StatusCode.OK, operation.reserve(1_025));
    stage(operation, 1_025);
    assertEquals(retainedBytes, operation.accountedBytes());
  }

  @Test
  void rejectsDemandBeyondTheSharedTransactionCeiling() {
    int maximum = DatabaseResourceDefaults.MAXIMUM_TRANSACTION_WRITE_ENTRIES;
    IndexedVersionOperation operation = new IndexedVersionOperation();
    assertEquals(-1, IndexedVersionOperation.required(maximum, 1));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, operation.reserve(maximum + 1));
    assertTrue(operation.accountedBytes() < 1_024);
  }

  private static void stage(IndexedVersionOperation operation, int count) {
    for (int index = 0; index < count; index++) {
      assertTrue(operation.canStage(0, false, 0));
      operation.stage(0, false, index, index);
    }
    assertEquals(count, operation.count());
  }
}
