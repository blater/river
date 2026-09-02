package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class IndexedLongOrdinalIndexTest {
  @Test
  void terminatesAtFullCapacityAndReusesOnlyOccupiedSlots() {
    IndexedLongOrdinalIndex index = new IndexedLongOrdinalIndex(1);
    assertEquals(StatusCode.OK, index.reserve(1));
    long retainedBytes = index.accountedBytes();
    assertTrue(index.add(17, 0));
    assertEquals(0, index.find(17));
    assertEquals(-1, index.find(18));
    assertFalse(index.add(18, 0));

    index.clear();
    assertEquals(retainedBytes, index.accountedBytes());
    assertTrue(index.add(18, 0));
    assertEquals(-1, index.find(17));
    assertEquals(0, index.find(18));
  }
}
