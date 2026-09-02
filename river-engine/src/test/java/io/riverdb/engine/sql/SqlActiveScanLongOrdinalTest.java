package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class SqlActiveScanLongOrdinalTest {
  @Test
  void admitsMaterializedCardinalityBeyondIntegerRange() {
    SqlActiveScanState state = new SqlActiveScanState();
    assertEquals(StatusCode.OK, state.claimSorted((long) Integer.MAX_VALUE + 1));
    assertEquals(0, state.currentSortedOrdinal());
    state.complete();
    assertEquals(StatusCode.OK, state.reset());
  }
}
