package io.riverdb.bench.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class PrimitiveVectorBatchTest {
  @Test
  void reusesSelectionVectorAcrossScans() {
    var batch = new PrimitiveVectorBatch(8);
    for (int row = 0; row < 8; row++) {
      batch.setRow(row, 100L + row, row * 10L);
    }

    assertEquals(4, batch.scanBalanceAtLeast(40L));
    assertEquals(4, batch.selectedRow(0));
    assertEquals(422L, batch.sumSelectedAccountIds());

    assertEquals(2, batch.scanBalanceAtLeast(60L));
    assertEquals(6, batch.selectedRow(0));
    assertEquals(213L, batch.sumSelectedAccountIds());
  }
}
