package io.riverdb.engine.runtime.materialized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SqlMaterializedLongPairIndexTest {
  @Test
  void removesAndReinsertsWithoutTombstoneGrowth() {
    SqlMaterializedLongPairIndex index = new SqlMaterializedLongPairIndex(16);
    for (int entry = 0; entry < 8; entry++) {
      assertTrue(index.put(entry + 1, entry * 17L, entry));
    }
    for (int entry = 0; entry < 8; entry += 2) {
      assertTrue(index.remove(entry + 1, entry * 17L));
    }
    for (int entry = 0; entry < 8; entry++) {
      int expected = (entry & 1) == 0 ? -1 : entry;
      assertEquals(expected, index.find(entry + 1, entry * 17L));
    }
    for (int entry = 8; entry < 16; entry++) {
      assertTrue(index.put(entry + 1, entry * 17L, entry));
      assertEquals(entry, index.find(entry + 1, entry * 17L));
    }
  }

  @Test
  void capacityChecksPrimitiveArrayRepresentation() {
    assertEquals(8, SqlMaterializedLongPairIndex.capacity(4, 2));
    assertEquals(-1, SqlMaterializedLongPairIndex.capacity(Integer.MAX_VALUE, 4));
  }
}
