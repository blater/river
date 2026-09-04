package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class SqlRetainedLongPagesTest {
  @Test
  void growsAcrossStructuralPagesAndParticipatesInBudgetReclamation() {
    SqlSessionShapeBudget budget = new SqlSessionShapeBudget(null);
    SqlRetainedLongPages values = new SqlRetainedLongPages(budget);

    assertEquals(StatusCode.OK, values.begin());
    for (int index = 0; index < 1_025; index++) {
      assertEquals(StatusCode.OK, values.append(index * 17L));
    }
    assertEquals(1_025, values.count());
    assertEquals(0, values.get(0));
    assertEquals(256L * 17, values.get(256));
    assertEquals(1_024L * 17, values.get(1_024));
    assertEquals(0, values.reclaimableRetainedBytes());

    values.finish();
    long retained = values.reclaimableRetainedBytes();
    assertTrue(retained > 5L * 256 * Long.BYTES);
    assertEquals(retained, budget.retainedBytes());
    assertEquals(StatusCode.OK, budget.release(retained));
    values.releaseRetainedStorage();
    assertEquals(0, budget.retainedBytes());
    assertEquals(0, values.reclaimableRetainedBytes());
  }
}
